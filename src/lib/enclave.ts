import { Buff }   from '@cmdcode/buff'
import { nip19 }  from 'nostr-tools'
import { gcm }    from '@noble/ciphers/aes.js'
import { pbkdf2 } from '@noble/hashes/pbkdf2.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { Assert } from '@vbyte/micro-lib/assert'

const PKDF_OPT = { c: 100000, dkLen: 32 }

export function create_encryption_key (
  password : string,
  salt     : Uint8Array
) : Uint8Array {
  const encoder = new TextEncoder()
  const pbytes  = encoder.encode(password)
  Assert.ok(salt.length >= 16, 'salt must be at least 16 bytes')
  return pbkdf2(sha256, pbytes, salt, PKDF_OPT)
}

export function encrypt_content (
  content  : string,
  password : string
) : string | null {
  try {
    const sbytes  = Buff.str(content)
    const vector  = Buff.random(24)
    const enc_key = create_encryption_key(password, vector)
    const payload = gcm(enc_key, vector).encrypt(sbytes)
    return new Buff(payload).b64url + '?iv=' + vector.b64url
  } catch {
    return null
  }
}

export function decrypt_content (
  content  : string,
  password : string
) : string | null {
  try {
    Assert.ok(content.includes('?iv='), 'encrypted content must include iv')
    const [ payload, iv ] = content.split('?iv=')
    const pbytes  = Buff.b64url(payload)
    const vector  = Buff.b64url(iv)
    const enc_key = create_encryption_key(password, vector)
    const seckey  = gcm(enc_key, vector).decrypt(pbytes)
    return new Buff(seckey).str
  } catch {
    return null
  }
}
