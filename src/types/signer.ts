export type NIP55Request     = GetPublicKeyRequest | SignEventRequest | EncryptRequest | DecryptRequest | DecryptZapRequest
export type NIP55Result      = NIP55AcceptResult | NIP55RejectResult
export type NIP55Action      = 'get_public_key' | 'sign_event' | 'nip04_encrypt' | 'nip04_decrypt' | 'nip44_encrypt' | 'nip44_decrypt' | 'decrypt_zap_event'
export type NIP55WindowAPI   = (request: NIP55Request) => Promise<NIP55Result>

// NIP-55 Request Types
export interface BaseNIP55Request {
  type         : NIP55Action
  id?          : string
  callbackUrl? : string
  host         : string
}

export interface BaseNIP55Result {
  ok     : boolean
  type   : NIP55Action
  id?    : string
}

export interface NIP55AcceptResult extends BaseNIP55Result {
  ok     : true
  result : any // Result of the request
}

export interface NIP55RejectResult extends BaseNIP55Result {
  ok     : false
  reason : string
}

export interface GetPublicKeyRequest extends BaseNIP55Request {
  type : 'get_public_key'
}

export interface SignEventRequest extends BaseNIP55Request {
  type  : 'sign_event'
  event : any // Event template to sign
}

export interface EncryptRequest extends BaseNIP55Request {
  type      : 'nip04_encrypt' | 'nip44_encrypt'
  plaintext : string
  pubkey    : string
}

export interface DecryptRequest extends BaseNIP55Request {
  type       : 'nip04_decrypt' | 'nip44_decrypt'
  ciphertext : string
  pubkey     : string
}

export interface DecryptZapRequest extends BaseNIP55Request {
  type  : 'decrypt_zap_event'
  event : any // Zap event to decrypt
}
