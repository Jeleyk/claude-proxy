import { UserStatsView } from './UserStatsView';

export function MyStats({ canReset }: { canReset: boolean }) {
  return (
    <div className="main-inner">
      <div className="section-head" style={{ marginTop: 0 }}>
        <div><h1>My statistics</h1><p className="sub" style={{ margin: 0 }}>Your own usage only — all sources, per token. Refreshes every 8s.</p></div>
      </div>
      <UserStatsView userId={null} canReset={canReset} />
    </div>
  );
}
