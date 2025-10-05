import { NodeInfoView } from './node.js'
import { PeerInfoView } from './peers.js'
import { ConsoleView }  from './console.js'

export function DashboardView () {
  return (
    <>
      <NodeInfoView />
      <PeerInfoView />
      <ConsoleView />
    </>
  )
}
