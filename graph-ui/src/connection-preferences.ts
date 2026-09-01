export interface ConnectionPreferences {
  remembered: boolean
  host: string
  port: string
  username: string
  privateKeyPath: string
  privateKeyMissing: boolean
}

export const emptyConnectionPreferences: ConnectionPreferences = {
  remembered: false,
  host: '',
  port: '22',
  username: '',
  privateKeyPath: '',
  privateKeyMissing: false,
}

export function parseConnectionPreferences(serialized: string): ConnectionPreferences {
  try {
    const value: unknown = JSON.parse(serialized)
    if (!value || typeof value !== 'object' || (value as { remembered?: unknown }).remembered !== true) return emptyConnectionPreferences
    const profile = value as Record<string, unknown>
    if (typeof profile.host !== 'string' || typeof profile.port !== 'number' || typeof profile.username !== 'string' || typeof profile.privateKeyPath !== 'string') return emptyConnectionPreferences
    if (!profile.host.trim() || profile.port < 1 || profile.port > 65535 || !profile.username.trim() || !profile.privateKeyPath.trim()) return emptyConnectionPreferences
    return {
      remembered: true,
      host: profile.host,
      port: String(profile.port),
      username: profile.username,
      privateKeyPath: profile.privateKeyPath,
      privateKeyMissing: profile.privateKeyExists === false,
    }
  } catch {
    return emptyConnectionPreferences
  }
}
