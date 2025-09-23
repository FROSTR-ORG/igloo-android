# Permissions Component

The Permissions component allows users to view and manage application permissions stored in the SettingsData. It provides two tables for managing different types of permissions:

## Features

### Action Permissions Table
- Displays permissions for specific actions external applications can perform
- Shows host, action name, status (Allowed/Denied), and creation timestamp
- Allows removal of individual action permissions via "×" button

### Event Permissions Table
- Displays permissions for event types external applications can access
- Shows host, event kind number, status (Allowed/Denied), and creation timestamp
- Allows removal of individual event permissions via "×" button

## Usage

```tsx
import { PermissionsView } from '@/components/permissions'

// Use in your settings or configuration UI
<PermissionsView />
```

## Data Structure

The component works with the `PermissionPolicy[]` structure from `@/types.js`:

- `PermActionRecord`: Contains host, action, accept status, and creation timestamp
- `PermEventRecord`: Contains host, event kind, accept status, and creation timestamp

## UI Features

- **Empty State**: Shows informative messages when no permissions exist
- **Status Indicators**: Visual indicators for allowed/denied permissions using existing status styles
- **Overflow Handling**: Long hostnames are truncated with ellipsis and show full text on hover
- **Save/Cancel**: Changes are tracked and can be saved or cancelled
- **Consistent Styling**: Uses existing global and settings CSS classes

## Integration

The component:
- Integrates with the settings context via `useSettings()`
- Stores permissions in `settings.data.perms`
- Follows the same patterns as other settings components (peers, relays)
- Supports real-time updates and persistence