import { useEffect, useState } from 'react';
import { api, RoleDto, UserDto } from '../api';

export function Users() {
  const [users, setUsers] = useState<UserDto[]>([]);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [allPerms, setAllPerms] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setUsers(await api.users());
      const r = await api.roles();
      setRoles(r.roles); setAllPerms(r.allPermissions);
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  if (err) return <div className="err">{err}</div>;

  return (
    <div>
      <h1>Users &amp; Roles</h1>
      <p className="sub">Manage who can sign in and what they can do.</p>

      <CreateUser roles={roles} onDone={load} />

      <h2>Users</h2>
      <table>
        <thead><tr><th>Username</th><th>Roles</th><th>Enabled</th><th>Permissions</th><th></th></tr></thead>
        <tbody>
          {users.map((u) => <UserRow key={u.id} u={u} roles={roles} onChange={load} />)}
        </tbody>
      </table>

      <h2>Roles</h2>
      {roles.map((r) => <RoleRow key={r.id} r={r} allPerms={allPerms} onChange={load} />)}
      <CreateRole allPerms={allPerms} onDone={load} />
    </div>
  );
}

function CreateUser({ roles, onDone }: { roles: RoleDto[]; onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [err, setErr] = useState<string | null>(null);

  function toggle(r: string) {
    setSelected((s) => s.includes(r) ? s.filter((x) => x !== r) : [...s, r]);
  }
  async function submit() {
    setErr(null);
    try {
      await api.createUser({ username, password, roles: selected });
      setUsername(''); setPassword(''); setSelected([]); onDone();
    } catch (e: any) { setErr(e.message); }
  }

  return (
    <div className="panel">
      <h2 style={{ marginTop: 0 }}>Create user</h2>
      <div className="grid2">
        <label className="field"><span>Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} /></label>
        <label className="field"><span>Password</span>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      </div>
      <div className="field">
        <span>Roles</span>
        <div className="checks">
          {roles.map((r) => (
            <label key={r.id}><input type="checkbox" checked={selected.includes(r.name)} onChange={() => toggle(r.name)} />{r.name}</label>
          ))}
        </div>
      </div>
      {err && <div className="err">{err}</div>}
      <button onClick={submit}>Create user</button>
    </div>
  );
}

function UserRow({ u, roles, onChange }: { u: UserDto; roles: RoleDto[]; onChange: () => void }) {
  const [editing, setEditing] = useState(false);
  const [selected, setSelected] = useState<string[]>(u.roles);
  const [password, setPassword] = useState('');

  function toggle(r: string) {
    setSelected((s) => s.includes(r) ? s.filter((x) => x !== r) : [...s, r]);
  }
  async function save() {
    await api.updateUser(u.id, { roles: selected, password: password || undefined });
    setEditing(false); setPassword(''); onChange();
  }
  async function toggleEnabled() { await api.updateUser(u.id, { enabled: !u.enabled }); onChange(); }
  async function del() { if (confirm(`Delete user ${u.username}?`)) { await api.deleteUser(u.id); onChange(); } }

  if (editing) {
    return (
      <tr>
        <td>{u.username}</td>
        <td colSpan={3}>
          <div className="checks">
            {roles.map((r) => (
              <label key={r.id}><input type="checkbox" checked={selected.includes(r.name)} onChange={() => toggle(r.name)} />{r.name}</label>
            ))}
          </div>
          <input style={{ marginTop: 8 }} type="password" placeholder="new password (optional)" value={password} onChange={(e) => setPassword(e.target.value)} />
        </td>
        <td>
          <div className="row">
            <button className="sm" onClick={save}>Save</button>
            <button className="sm ghost" onClick={() => setEditing(false)}>Cancel</button>
          </div>
        </td>
      </tr>
    );
  }

  return (
    <tr>
      <td>{u.username}</td>
      <td>{u.roles.join(', ') || <span className="hint">none</span>}</td>
      <td><span className={`badge ${u.enabled ? 'ok' : 'muted'}`}>{u.enabled ? 'on' : 'off'}</span></td>
      <td className="hint">{u.permissions.length} perms</td>
      <td>
        <div className="row">
          <button className="sm ghost" onClick={() => setEditing(true)}>Edit</button>
          <button className="sm ghost" onClick={toggleEnabled}>{u.enabled ? 'Disable' : 'Enable'}</button>
          <button className="sm danger" onClick={del}>Delete</button>
        </div>
      </td>
    </tr>
  );
}

function RoleRow({ r, allPerms, onChange }: { r: RoleDto; allPerms: string[]; onChange: () => void }) {
  const [selected, setSelected] = useState<string[]>(r.permissions);
  const [dirty, setDirty] = useState(false);

  function toggle(p: string) {
    setSelected((s) => { setDirty(true); return s.includes(p) ? s.filter((x) => x !== p) : [...s, p]; });
  }
  async function save() { await api.updateRole(r.id, { permissions: selected }); setDirty(false); onChange(); }
  async function del() { if (confirm(`Delete role ${r.name}?`)) { await api.deleteRole(r.id); onChange(); } }

  return (
    <div className="panel" style={{ maxWidth: 760 }}>
      <div className="toolbar">
        <b>{r.name}</b>
        <div className="right row">
          {dirty && <button className="sm" onClick={save}>Save</button>}
          <button className="sm danger" onClick={del}>Delete</button>
        </div>
      </div>
      <div className="checks">
        {allPerms.map((p) => (
          <label key={p}><input type="checkbox" checked={selected.includes(p)} onChange={() => toggle(p)} />{p}</label>
        ))}
      </div>
    </div>
  );
}

function CreateRole({ allPerms, onDone }: { allPerms: string[]; onDone: () => void }) {
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  function toggle(p: string) { setSelected((s) => s.includes(p) ? s.filter((x) => x !== p) : [...s, p]); }
  async function submit() { await api.createRole({ name, permissions: selected }); setName(''); setSelected([]); onDone(); }

  return (
    <div className="panel" style={{ maxWidth: 760 }}>
      <h2 style={{ marginTop: 0 }}>Create role</h2>
      <label className="field"><span>Name</span>
        <input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="checks">
        {allPerms.map((p) => (
          <label key={p}><input type="checkbox" checked={selected.includes(p)} onChange={() => toggle(p)} />{p}</label>
        ))}
      </div>
      <div style={{ marginTop: 12 }}><button onClick={submit}>Create role</button></div>
    </div>
  );
}
