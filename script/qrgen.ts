import qrcode from 'qrcode-terminal'

const input = process.argv[2]

if (!input) {
  console.error('Usage: npm run qrgen <string>')
  console.error('Example: npm run qrgen "wss://relay.ngrok.dev"')
  process.exit(1)
}

console.log(`\nQR Code for: ${input}\n`)
qrcode.generate(input, { small: true })
console.log('')
