import type { BifrostNode } from '@frostr/bifrost'
import type { SignatureEntry } from '@frostr/bifrost'

/**
 * Queued signing request with promise resolution callbacks
 */
interface QueuedSignRequest {
  eventId: string
  resolve: (entry: SignatureEntry) => void
  reject: (error: Error) => void
}

/**
 * Batched signing queue for FROSTR bifrost operations
 *
 * Collects sign_event requests and sends them to bifrost in batches.
 * Only ONE batch is in flight at a time to avoid overwhelming the network.
 * Deduplicates by eventId - multiple requests for the same event share one signature.
 *
 * Usage:
 *   const queue = new SigningBatchQueue(node)
 *   const signature = await queue.add(eventId)
 */
export class SigningBatchQueue {
  private processing: boolean = false
  private timer: ReturnType<typeof setTimeout> | null = null
  private readonly node: BifrostNode

  // Deduplication: map eventId -> list of waiting callers
  private pendingByEventId: Map<string, QueuedSignRequest[]> = new Map()

  // Cache recent results for deduplication
  private resultCache: Map<string, { entry: SignatureEntry, timestamp: number }> = new Map()
  private readonly CACHE_TTL_MS = 5000

  // Batch timing
  private readonly BATCH_DELAY_MS = 50 // Short delay to collect concurrent requests
  private readonly SIGN_TIMEOUT_MS = 8000 // Max time to wait for bifrost response
  private readonly MAX_QUEUE_SIZE = 10 // Reject new requests when queue exceeds this
  private readonly RECONNECT_TIMEOUT_MS = 5000 // Max time to wait for reconnection

  // Track consecutive failures to detect dead connections
  private consecutiveFailures: number = 0
  private readonly MAX_FAILURES_BEFORE_RECONNECT = 2

  constructor(node: BifrostNode) {
    this.node = node
  }

  /**
   * Add a signing request to the queue
   * Returns a promise that resolves with the SignatureEntry when signing completes
   */
  add(eventId: string): Promise<SignatureEntry> {
    return new Promise((resolve, reject) => {
      // Check cache first (works even if disconnected)
      const cached = this.resultCache.get(eventId)
      if (cached && Date.now() - cached.timestamp < this.CACHE_TTL_MS) {
        resolve(cached.entry)
        return
      }

      // Check if this eventId is already pending (always allow - it's a duplicate)
      const pending = this.pendingByEventId.get(eventId)
      if (pending) {
        // Already have this eventId pending - just add another caller
        pending.push({ eventId, resolve, reject })
        return
      }

      // Reject if queue is full (backpressure - fail fast instead of buffering spam)
      if (this.pendingByEventId.size >= this.MAX_QUEUE_SIZE) {
        reject(new Error('Signing queue full - try again later'))
        return
      }

      // New eventId - add to queue
      this.pendingByEventId.set(eventId, [{ eventId, resolve, reject }])

      // Schedule batch processing
      this.scheduleBatch()
    })
  }

  /**
   * Try to reconnect the bifrost node
   * Returns true if reconnection succeeds within timeout
   */
  private async tryReconnect(): Promise<boolean> {
    console.log('[BatchQueue] Attempting reconnect, is_ready:', this.node.is_ready)

    // Trigger reconnection
    try {
      this.node.connect()
    } catch (e) {
      console.log('[BatchQueue] connect() threw:', e)
    }

    // Wait for node to become ready (poll with timeout)
    const startTime = Date.now()
    while (Date.now() - startTime < this.RECONNECT_TIMEOUT_MS) {
      if (this.node.is_ready) {
        console.log('[BatchQueue] Reconnected successfully')
        return true
      }
      // Wait 100ms before checking again
      await new Promise(resolve => setTimeout(resolve, 100))
    }

    console.log('[BatchQueue] Reconnect timeout, is_ready:', this.node.is_ready)
    return false
  }

  /**
   * Schedule batch processing with a short delay to collect concurrent requests
   */
  private scheduleBatch(): void {
    if (this.timer !== null) return // Already scheduled
    if (this.processing) return // Will be scheduled after current batch completes

    this.timer = setTimeout(() => {
      this.timer = null
      this.processBatch()
    }, this.BATCH_DELAY_MS)
  }

  /**
   * Process all pending requests as a single batch
   */
  private async processBatch(): Promise<void> {
    if (this.processing) return
    if (this.pendingByEventId.size === 0) return

    this.processing = true

    // Take snapshot of current unique pending eventIds
    // (Map keys are inherently unique, but be explicit)
    const eventIds = [...new Set(this.pendingByEventId.keys())]

    try {
      console.log('[BatchQueue] Processing batch of', eventIds.length, 'eventIds, is_ready:', this.node.is_ready, 'failures:', this.consecutiveFailures)

      // If node reports disconnected OR we've had too many consecutive failures, try to reconnect
      const needsReconnect = !this.node.is_ready || this.consecutiveFailures >= this.MAX_FAILURES_BEFORE_RECONNECT

      if (needsReconnect) {
        console.log('[BatchQueue] Connection appears dead, forcing reconnect')
        const reconnected = await this.tryReconnect()
        if (!reconnected) {
          const error = new Error('Bifrost node disconnected - reconnection failed')
          for (const eventId of eventIds) {
            this.rejectPending(eventId, error)
          }
          return
        }
        // Reset failure counter after successful reconnect
        this.consecutiveFailures = 0
      }

      // Wrap bifrost call with timeout to prevent queue from getting stuck
      // if WebSocket connection dies and nostr-p2p doesn't timeout properly
      const signWithTimeout = async (): Promise<any> => {
        return new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            reject(new Error('Bifrost sign timeout - connection may be dead'))
          }, this.SIGN_TIMEOUT_MS)

          const signPromise = eventIds.length === 1
            ? this.node.req.sign(eventIds[0])
            : this.node.req.sign(eventIds.map(id => [id] as [string]))

          signPromise
            .then(result => {
              clearTimeout(timer)
              resolve(result)
            })
            .catch(err => {
              clearTimeout(timer)
              reject(err)
            })
        })
      }

      const res = await signWithTimeout()

      if (!res.ok) {
        // Track failure - connection might be dead even though is_ready says true
        this.consecutiveFailures++
        console.log('[BatchQueue] Batch returned error, failures now:', this.consecutiveFailures)
        const error = new Error(res.err)
        for (const eventId of eventIds) {
          this.rejectPending(eventId, error)
        }
        return
      }

      // Success - reset failure counter
      this.consecutiveFailures = 0

      // Build map of sighash -> SignatureEntry
      const sigMap = new Map<string, SignatureEntry>()
      for (const entry of res.data || []) {
        sigMap.set(entry[0], entry)
        // Cache successful results
        this.resultCache.set(entry[0], { entry, timestamp: Date.now() })
      }

      // Clean old cache entries
      if (this.resultCache.size > 100) {
        const now = Date.now()
        for (const [key, value] of this.resultCache) {
          if (now - value.timestamp > this.CACHE_TTL_MS) {
            this.resultCache.delete(key)
          }
        }
      }

      // Resolve/reject each eventId
      for (const eventId of eventIds) {
        const entry = sigMap.get(eventId)
        if (entry) {
          this.resolvePending(eventId, entry)
        } else {
          this.rejectPending(eventId, new Error(`Signature not found for ${eventId}`))
        }
      }

    } catch (error) {
      // Track failure - likely timeout means connection is dead
      this.consecutiveFailures++
      console.log('[BatchQueue] Batch threw exception, failures now:', this.consecutiveFailures, 'error:', error)
      const err = error instanceof Error ? error : new Error(String(error))
      for (const eventId of eventIds) {
        this.rejectPending(eventId, err)
      }
    } finally {
      this.processing = false

      // If more requests accumulated during processing, schedule next batch
      if (this.pendingByEventId.size > 0) {
        this.scheduleBatch()
      }
    }
  }

  private resolvePending(eventId: string, entry: SignatureEntry): void {
    const pending = this.pendingByEventId.get(eventId)
    if (pending) {
      pending.forEach(req => req.resolve(entry))
      this.pendingByEventId.delete(eventId)
    }
  }

  private rejectPending(eventId: string, error: Error): void {
    const pending = this.pendingByEventId.get(eventId)
    if (pending) {
      pending.forEach(req => req.reject(error))
      this.pendingByEventId.delete(eventId)
    }
  }

  /**
   * Get count of unique pending eventIds
   */
  get pending(): number {
    return this.pendingByEventId.size
  }
}
