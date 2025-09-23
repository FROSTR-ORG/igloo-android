import { RefreshIcon }    from '@/components/util/icons.js'
import { useBifrostNode } from '@/context/node.js'

export function PeerInfoView () {
  const node = useBifrostNode()

  return (
    <div className="dashboard-container">
      <h2 className="section-header">Peer Info</h2>
      {node.status === 'init' && (
        <p>waiting for node to initialize...</p>
      )}
      {node.status === 'locked' && (
        <p>waiting for node to unlock...</p>
      )}
      {node.status === 'online' && node.peers && (
        <table className="peers-table">
          <thead>
            <tr>
              <th>Pubkey</th>
              <th>Status</th>
              <th>Refresh</th>
            </tr>
          </thead>
          <tbody>
            {node.peers.map((peer) => (
              <tr key={peer.pubkey}>
                <td className="pubkey-cell">{peer.pubkey}</td>
                <td className="status-cell">
                  <span className={`status-indicator ${peer.status}`}>
                    {peer.status}
                  </span>
                </td>
                <td className="refresh-cell">
                  <button
                    className="button"
                    onClick={() => node.ping(peer.pubkey)}
                    disabled={node.status !== 'online'}
                    title="Refresh peer status"
                  >
                    <RefreshIcon />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
