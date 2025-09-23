import { Buff }  from '@vbyte/buff'
import { nip19 } from 'nostr-tools'

export function decode_nsec (secret: string) : Uint8Array | null {
  try {
    if (secret.startsWith('nsec1')) {
      const decoded = nip19.decode(secret).data as Uint8Array
      return Buff.bytes(decoded)
    } else if (Buff.is_hex(secret) && secret.length === 64) {
      return Buff.bytes(secret)
    } else {
      return null
    }
  } catch {
    return null
  }
}

export function encode_nsec (seckey: string) : string | null {
  try {
    const sbytes = Buff.bytes(seckey)
    return nip19.nsecEncode(sbytes)
  } catch {
    return null
  }
}
