import {
  encode_group_pkg,
  encode_share_pkg
} from '@frostr/bifrost/encoder'

import { generate_dealer_pkg } from '@frostr/bifrost/lib'
import qrcode from 'qrcode-terminal'

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

for (const share of pkg.shares) {
  const enc_share = encode_share_pkg(share)
  console.log(`==== [ Share ${share.idx} Package ] `.padEnd(80, '=') + '\n')
  console.log(enc_share + '\n')

  console.log(`Share ${share.idx} QR Code:`)
  qrcode.generate(enc_share, { small: true })
  console.log('')
}
