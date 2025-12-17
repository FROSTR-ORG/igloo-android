import {
  encode_group_pkg,
  encode_share_pkg
} from '@frostr/bifrost/encoder'

import { generate_dealer_pkg } from '@frostr/bifrost/lib'
import qrcode from 'qrcode-terminal'
import fs from 'fs'
import path from 'path'

const shares    = parseInt(process.argv[2] ?? '3')
const threshold = parseInt(process.argv[3] ?? '2')

if (threshold > shares) {
  console.error('Threshold must be less than or equal to the number of shares')
  process.exit(1)
}

const pkg = generate_dealer_pkg(threshold, shares)

const enc_group = encode_group_pkg(pkg.group)
console.log(`==== [ Group Package ] `.padEnd(80, '=') + '\n')
console.log(enc_group + '\n')

console.log('Group Package QR Code:')
qrcode.generate(enc_group, { small: true })
console.log('')

const encodedShares: { idx: number; encoded: string }[] = []

for (const share of pkg.shares) {
  const enc_share = encode_share_pkg(share)
  encodedShares.push({ idx: share.idx, encoded: enc_share })

  console.log(`==== [ Share ${share.idx} Package ] `.padEnd(80, '=') + '\n')
  console.log(enc_share + '\n')

  console.log(`Share ${share.idx} QR Code:`)
  qrcode.generate(enc_share, { small: true })
  console.log('')
}

// Save keyset to file
const keyset = {
  group: enc_group,
  shares: encodedShares,
  threshold,
  totalShares: shares,
  createdAt: new Date().toISOString()
}

const keysetPath = path.join(process.cwd(), 'keyset.json')
fs.writeFileSync(keysetPath, JSON.stringify(keyset, null, 2))
console.log(`==== [ Keyset Saved ] `.padEnd(80, '=') + '\n')
console.log(`Keyset saved to: ${keysetPath}\n`)
