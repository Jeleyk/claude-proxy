import { useEffect, useState } from 'react';
import { api, GroupDto, RoleDto, UserDto } from '../api';
import { Check, Modal, Switch } from '../ui';

export function Users() {
  const [users, setUsers] = useState<UserDto[]>([]);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [groups, setGroups] = useState<GroupDto[]>([]);
  const [allPerms, setAllPerms] = useState<string[]>([]);
  const [editing, setEditing] = useState<UserDto | 'new' | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    try {
      setUsers(await api.users());
      const r = await api.roles(); setRoles(r.roles); setAllPerms(r.allPermissions);
      setGroups(await api.groups().catch(() => []));
    } catch (e: any) { setErr(e.message); }
  }
  useEffect(() => { load(); }, []);

  if (err) return <div className="err">{err}</div>;

  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>Users &amp; Roles</h1><p className="sub" style={{ margin: 0 }}>Who can sign in, what they can do, and which account groups they may use.</p></div>
        <button onClick={() => setEditing('new')}>+ New user</button>
      </div>

      <h2>Users</h2>
      <div className="tablewrap">
        <table>
          <thead><tr><th>Username</th><th>Roles</th><th>Group access</th><th>Enabled</th><th></th></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td><b>{u.username}</b></td>
                <td><div className="pillrow">{u.roles.length ? u.roles.map((r) => <span key={r} className="grouptag">{r}</span>) : <span className="hint">none</span>}</div></td>
                <td>
                  {u.allGroups ? <span className="badge accent">all groups</span>
                    : u.allowedGroups.length
                      ? <div className="pillrow">{u.allowedGroups.map((gid) => <span key={gid} className="grouptag">{groups.find((g) => g.id === gid)?.name ?? `#${gid}`}</span>)}</div>
                      : <span className="hint">ungrouped only</span>}
                </td>
                <td><Switch checked={u.enabled} onChange={async (v) => { await api.updateUser(u.id, { enabled: v }); load(); }} /></td>
                <td>
                  <div className="row">
                    <button className="sm ghost" onClick={() => setEditing(u)}>Edit</button>
                    <button className="sm danger" onClick={async () => { if (confirm(`Delete ${u.username}?`)) { await api.deleteUser(u.id); load(); } }}>Delete</button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Roles</h2>
      {roles.map((r) => <RoleRow key={r.id} r={r} allPerms={allPerms} onChange={load} />)}
      <CreateRole allPerms={allPerms} onDone={load} />

      {editing && (
        <UserModal
          user={editing === 'new' ? null : editing}
          roles={roles} groups={groups}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load(); }}
        />
      )}
    </div>
  );
}

function UserModal({ user, roles, groups, onClose, onSaved }: {
  user: UserDto | null; roles: RoleDto[]; groups: GroupDto[]; onClose: () => void; onSaved: () => void;
}) {
  const isNew = user == null;
  const [username, setUsername] = useState(user?.username ?? '');
  const [password, setPassword] = useState('');
  const [selRoles, setSelRoles] = useState<string[]>(user?.roles ?? []);
  const [selGroups, setSelGroups] = useState<number[]>(user?.allowedGroups ?? []);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const toggleRole = (r: string) => setSelRoles((s) => s.includes(r) ? s.filter((x) => x !== r) : [...s, r]);
  const toggleGroup = (g: number) => setSelGroups((s) => s.includes(g) ? s.filter((x) => x !== g) : [...s, g]);
  const isAdmin = selRoles.some((rn) => roles.find((r) => r.name === rn)?.permissions.includes('ADMIN'));

  async function save() {
    setErr(null); setBusy(true);
    try {
      if (isNew) await api.createUser({ username, password, roles: selRoles, allowedGroups: selGroups });
      else await api.updateUser(user!.id, { password: password || undefined, roles: selRoles, allowedGroups: selGroups });
      onSaved();
    } catch (e: any) { setErr(e.message); setBusy(false); }
  }

  return (
    <Modal
      title={isNew ? 'Create user' : `Edit ${user!.username}`}
      onClose={onClose}
      footer={<>
        <button className="ghost" onClick={onClose}>Cancel</button>
        <button disabled={busy} onClick={save}>{busy ? '…' : 'Save'}</button>
      </>}
    >
      {isNew && <label className="field"><span>Username</span><input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus /></label>}
      <label className="field"><span>{isNew ? 'Password' : 'New password (leave blank to keep)'}</span><input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>

      <div className="field"><span>Roles</span>
        <div className="checks">
          {roles.map((r) => <Check key={r.id} checked={selRoles.includes(r.name)} onChange={() => toggleRole(r.name)} label={r.name} />)}
        </div>
      </div>

      <div className="field">
        <span>Account group access {isAdmin && <span className="badge accent" style={{ marginLeft: 6 }}>admin → all groups</span>}</span>
        {isAdmin ? <p className="hint">Admins can use every account regardless of group.</p> : (
          <div className="checks">
            {groups.length === 0 && <span className="hint">No groups defined. User can use ungrouped accounts.</span>}
            {groups.map((g) => <Check key={g.id} checked={selGroups.includes(g.id)} onChange={() => toggleGroup(g.id)} label={`${g.name} (${g.accountCount})`} />)}
          </div>
        )}
        <p className="hint" style={{ marginTop: 8 }}>Ungrouped accounts are always usable by any proxy user.</p>
      </div>
      {err && <div className="err">{err}</div>}
    </Modal>
  );
}

function RoleRow({ r, allPerms, onChange }: { r: RoleDto; allPerms: string[]; onChange: () => void }) {
  const [selected, setSelected] = useState<string[]>(r.permissions);
  const [dirty, setDirty] = useState(false);
  const toggle = (p: string) => { setSelected((s) => s.includes(p) ? s.filter((x) => x !== p) : [...s, p]); setDirty(true); };
  return (
    <div className="panel narrow" style={{ maxWidth: 760 }}>
      <div className="section-head" style={{ margin: '0 0 12px' }}>
        <b>{r.name}</b>
        <div className="row">
          {dirty && <button className="sm" onClick={async () => { await api.updateRole(r.id, { permissions: selected }); setDirty(false); onChange(); }}>Save</button>}
          <button className="sm danger" onClick={async () => { if (confirm(`Delete role ${r.name}?`)) { await api.deleteRole(r.id); onChange(); } }}>Delete</button>
        </div>
      </div>
      <div className="checks">{allPerms.map((p) => <Check key={p} checked={selected.includes(p)} onChange={() => toggle(p)} label={p} />)}</div>
    </div>
  );
}

function CreateRole({ allPerms, onDone }: { allPerms: string[]; onDone: () => void }) {
  const [name, setName] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const toggle = (p: string) => setSelected((s) => s.includes(p) ? s.filter((x) => x !== p) : [...s, p]);
  return (
    <div className="panel narrow" style={{ maxWidth: 760 }}>
      <h2 style={{ marginTop: 0 }}>Create role</h2>
      <label className="field"><span>Name</span><input value={name} onChange={(e) => setName(e.target.value)} /></label>
      <div className="checks">{allPerms.map((p) => <Check key={p} checked={selected.includes(p)} onChange={() => toggle(p)} label={p} />)}</div>
      <div style={{ marginTop: 14 }}><button onClick={async () => { if (name.trim()) { await api.createRole({ name, permissions: selected }); setName(''); setSelected([]); onDone(); } }}>Create role</button></div>
    </div>
  );
}
