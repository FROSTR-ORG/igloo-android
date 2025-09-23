import { useState }        from 'react'
import { DashboardView   } from '@/components/dash/index.js'
import { PermissionsView } from '@/components/permissions/index.js'
import { SettingsView }    from '@/components/settings/index.js'

import type { ReactElement } from 'react'

import * as Icons from '@/components/util/icons.js'

export function Tabs(): ReactElement {
  const [ activeTab, setActiveTab ] = useState('dashboard')

  return (
    <div className="tabs-container">
      <div className="tabs-nav-wrapper">

        <div className="tabs-navigation">
          <button 
            className={`tab-button ${activeTab === 'dashboard' ? 'active' : ''}`}
            onClick={() => setActiveTab('dashboard')}
          >
            <Icons.ConsoleIcon />
            <span>Dashboard</span>
          </button>

          <button 
            className={`tab-button ${activeTab === 'permissions' ? 'active' : ''}`}
            onClick={() => setActiveTab('permissions')}
          >
            <Icons.PermissionsIcon />
            <span>Permissions</span>
          </button>

          <button
            className={`tab-button ${activeTab === 'settings' ? 'active' : ''}`}
            onClick={() => setActiveTab('settings')}
          >
            <Icons.SettingsIcon />
            <span>Settings</span>
          </button>
        </div>
      </div>

      <div className="tab-content">
        {activeTab === 'dashboard' && (
          <div className="tab-panel">
            <DashboardView />
          </div>
        )}

        {activeTab === 'permissions' && (
          <div className="tab-panel">
            <PermissionsView />
          </div>
        )}

        {activeTab === 'settings' && (
          <div className="tab-panel">
            <SettingsView />
          </div>
        )}
      </div>
    </div>
  )
}
