import {
  encode_group_pkg,
  encode_share_pkg
} from '@frostr/bifrost/encoder'

import { generate_dealer_pkg } from '@frostr/bifrost/lib'
import qrcode from 'qrcode-terminal'
import fs from 'fs'
import path from 'path'

const keysetPath = path.join(process.cwd(), 'keyset.json')

// Check if keyset.json already exists
if (fs.existsSync(keysetPath)) {
  console.log(`==== [ Existing Keyset Found ] `.padEnd(80, '=') + '\n')
  console.log(`Reading keyset from: ${keysetPath}\n`)

  const keyset = JSON.parse(fs.readFileSync(keysetPath, 'utf-8'))

  // Display group package
  console.log(`==== [ Group Package ] `.padEnd(80, '=') + '\n')
  console.log(keyset.group + '\n')

  console.log('Group Package QR Code:')
  qrcode.generate(keyset.group, { small: true })
  console.log('')

  // Display share packages
  for (const share of keyset.shares) {
    console.log(`==== [ Share ${share.idx} Package ] `.padEnd(80, '=') + '\n')
    console.log(share.encoded + '\n')

    console.log(`Share ${share.idx} QR Code:`)
    qrcode.generate(share.encoded, { small: true })
    console.log('')
  }

  console.log(`==== [ Keyset Info ] `.padEnd(80, '=') + '\n')
  console.log(`Threshold: ${keyset.threshold}`)
  console.log(`Total Shares: ${keyset.totalShares}`)
  console.log(`Created: ${keyset.createdAt}\n`)
  console.log('To generate a new keyset, delete keyset.json and run this script again.\n')

} else {
  // Generate new keyset
  const shares    = parseInt(process.argv[2] ?? '3')
  const threshold = parseInt(process.argv[3] ?? '2')

  if (threshold > shares) {
    console.error('Threshold must be less than or equal to the number of shares')
    process.exit(1)
  }

  console.log(`==== [ Generating New Keyset ] `.padEnd(80, '=') + '\n')
  console.log(`Threshold: ${threshold}, Total Shares: ${shares}\n`)

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

  fs.writeFileSync(keysetPath, JSON.stringify(keyset, null, 2))
  console.log(`==== [ Keyset Saved ] `.padEnd(80, '=') + '\n')
  console.log(`Keyset saved to: ${keysetPath}\n`)
}
