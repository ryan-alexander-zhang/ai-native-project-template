// node-pty ships prebuilt binaries and makes its spawn-helper executable from its
// own install script. npm blocks dependency install scripts unless approved, and
// without the executable bit every pty spawn fails with "posix_spawnp failed".
// Restoring the bit here keeps `npm install` self-sufficient; it is a no-op on
// Windows and wherever node-pty's own script already ran.
import { chmodSync, existsSync } from 'node:fs'

const helper = new URL(
  `../node_modules/node-pty/prebuilds/${process.platform}-${process.arch}/spawn-helper`,
  import.meta.url,
)

if (existsSync(helper)) {
  chmodSync(helper, 0o755)
}
