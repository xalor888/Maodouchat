// better-harness report — Maodouchat
// 数据源：同目录 findings.json / canvas.json（生成于 2026-08-21 00:46 +08:00）
// 自包含组件：无外部依赖，仅使用内联样式渲染。

const SEVERITY_COLOR = {
  high: "#e5484d",
  medium: "#f5a623",
  low: "#8b93a1",
};

const SEVERITY_LABEL = {
  high: "高",
  medium: "中",
  low: "低",
};

const scores = [
  { key: "CI 自动化", value: 86 },
  { key: "发版安全", value: 70 },
  { key: "测试金字塔", value: 55 },
  { key: "上下文卫生", value: 45 },
  { key: "Agent 验证环路", value: 30 },
  { key: "文件规模健康度", value: 25 },
];

const severity = { high: 2, medium: 4, low: 2 };

const largestFiles = [
  { name: "Routing.kt", lines: 17194 },
  { name: "ChatDetailScreen.kt", lines: 10660 },
  { name: "ChatDetailViewModel.kt", lines: 9557 },
  { name: "progress-report.md", lines: 6501 },
];

const findings = [
  { id: "F1", severity: "high", category: "verification-loop", title: "Agent 工作流被明令跳过编译与测试", detail: "8 个 docs/agent-prompts/B*.md 均写「只写代码不做任何测试和编译」；progress-report §7 固化为「少编译、少全测」。缺陷被推迟到 CI 才暴露，本地无反馈环路。", action: "「交付」定义改为：受影响模块增量编译 + 目标测试通过；禁止零验证。" },
  { id: "F2", severity: "high", category: "file-scale", title: "巨石文件迫使带外脚本整文件重写", detail: "Routing.kt 17194 行、ChatDetail 双文件 20217 行；agent 红线「禁改巨型文件」，实际靠 write_chatdetail.py 等 Python 整文件覆盖写绕过，diff 不可审。", action: "按域拆分 Routing.kt（Ktor 多 Routing 插件）；CI 加单文件 5000 行护栏。" },
  { id: "F3", severity: "medium", category: "release-safety", title: "缺签名密钥时静默降级 debug 签名发布", detail: "release.yml 未配置 KEYSTORE_BASE64 时生成临时 debug keystore，仅 ::warning:: 即照常发布，tag 正式发版路径同样适用。", action: "tag 触发缺密钥直接 exit 1；仅手动触发允许降级并标注非生产签名。" },
  { id: "F4", severity: "medium", category: "ci-coverage", title: "i18n 对称检查未接入 CI", detail: "check-string-parity.py 只是 release-checklist 手勾项；CI 仅跑 check-brand-terminology.py，与 B8「字符串只增不删」的高频改动冲突。", action: "ci.yml 追加一步 python3 scripts/check-string-parity.py。" },
  { id: "F5", severity: "medium", category: "portability", title: "Harness 入口硬编码 Windows 路径", detail: "agent prompt 与 run_verify.bat 钉死 D:\\Maodouchat，当前 macOS 工作区全部不可用。", action: "改用仓库相对路径；补 POSIX 等价验证脚本。" },
  { id: "F6", severity: "low", category: "repo-hygiene", title: "一次性补丁脚本永久驻留 scripts/", detail: "fix_*.py / final_cdvm.py / write_chatdetail.py 等会话产物与 CI 依赖脚本平级，无归属无文档。", action: "归档到 scripts/oneoff/ 或删除；scripts/README 标注 CI 引用关系。" },
  { id: "F7", severity: "medium", category: "test-pyramid", title: "端侧验收全靠人工填表", detail: "E2EE/通话/附件/UI 四大门禁对应 *-verification.md 人工表格；androidTest 仅 3 个文件，客户端无自动化 E2E 层。", action: "可脚本化部分下沉为集成/JVM 测试；验收文档强制签核人+日期。" },
  { id: "F8", severity: "low", category: "context-hygiene", title: "progress-report.md 无界增长至 6501 行", detail: "作为后续 agent 的主上下文，历史轮次（1.37x 编号）在前、现行约定（§4-§7）在 4000+ 行之后，信噪比持续下降。", action: "现行约定抽成 AGENTS.md 置顶；历史轮次归档 docs/archive/。" },
];

const strengths = [
  "CI 编号注释沉淀失败教训（9.244 setsid 防孤儿 JVM / 9.245 selfhost compose 预校验）",
  "docker-config job 用 Python 断言把生产网络拓扑变成机器门禁",
  ":app:verifyReleaseSize 包体基线在 CI 强制执行",
  "品牌术语检查作为 CI 第一步，低成本拦截文案漂移",
  "机器门禁与人工验收证据分层清晰，差距诚实记录",
];

const styles = {
  page: { fontFamily: "-apple-system, 'PingFang SC', 'Segoe UI', sans-serif", color: "#1f2329", background: "#f7f8fa", padding: 24, maxWidth: 960, margin: "0 auto" },
  h1: { fontSize: 22, margin: "0 0 4px" },
  sub: { color: "#6b7280", fontSize: 13, margin: "0 0 20px" },
  card: { background: "#ffffff", border: "1px solid #e6e8eb", borderRadius: 10, padding: 16, marginBottom: 16 },
  cardTitle: { fontSize: 14, fontWeight: 600, margin: "0 0 12px", color: "#374151" },
  grid2: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 },
  bar: { height: 8, borderRadius: 4, background: "#eef0f3", overflow: "hidden" },
  badge: (color) => ({ display: "inline-block", fontSize: 11, fontWeight: 600, color: "#fff", background: color, borderRadius: 4, padding: "1px 7px", marginRight: 8 }),
  findingTitle: { fontSize: 14, fontWeight: 600, margin: "0 0 4px" },
  findingMeta: { fontSize: 11, color: "#9aa1ac", fontFamily: "ui-monospace, monospace", margin: "0 0 6px" },
  p: { fontSize: 13, lineHeight: 1.6, margin: "0 0 6px", color: "#374151" },
  action: { fontSize: 12, color: "#0a7d33", margin: 0, lineHeight: 1.5 },
};

function ScoreBar({ label, value }) {
  const color = value >= 70 ? "#2f9e5f" : value >= 45 ? "#f5a623" : "#e5484d";
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, marginBottom: 3 }}>
        <span>{label}</span>
        <span style={{ color, fontWeight: 600 }}>{value}</span>
      </div>
      <div style={styles.bar}>
        <div style={{ width: `${value}%`, height: "100%", background: color }} />
      </div>
    </div>
  );
}

function FileBar({ name, lines, max }) {
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ display: "flex", justifyContent: "space-between", fontSize: 12, marginBottom: 3 }}>
        <span style={{ fontFamily: "ui-monospace, monospace" }}>{name}</span>
        <span style={{ color: lines > 10000 ? "#e5484d" : "#6b7280", fontWeight: 600 }}>{lines.toLocaleString()} 行</span>
      </div>
      <div style={styles.bar}>
        <div style={{ width: `${(lines / max) * 100}%`, height: "100%", background: lines > 10000 ? "#e5484d" : "#f5a623" }} />
      </div>
    </div>
  );
}

export default function BetterHarnessReport() {
  const maxLines = largestFiles[0].lines;
  return (
    <div style={styles.page}>
      <h1 style={styles.h1}>Maodouchat Harness 实践洞察</h1>
      <p style={styles.sub}>better-harness · 2026-08-21 00:46 · 8 项保留问题（2 高 / 4 中 / 2 低）+ 5 项优势实践</p>

      <div style={styles.grid2}>
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>Harness 健康度评分</h2>
          {scores.map((s) => (
            <ScoreBar key={s.key} label={s.key} value={s.value} />
          ))}
        </div>
        <div style={styles.card}>
          <h2 style={styles.cardTitle}>最大文件（巨石风险）</h2>
          {largestFiles.map((f) => (
            <FileBar key={f.name} name={f.name} lines={f.lines} max={maxLines} />
          ))}
          <div style={{ display: "flex", gap: 16, marginTop: 12 }}>
            {Object.keys(severity).map((sev) => (
              <span key={sev} style={{ fontSize: 12 }}>
                <span style={styles.badge(SEVERITY_COLOR[sev])}>{SEVERITY_LABEL[sev]}</span>
                {severity[sev]} 项
              </span>
            ))}
          </div>
        </div>
      </div>

      <div style={styles.card}>
        <h2 style={styles.cardTitle}>保留问题（按严重度排序）</h2>
        {findings.map((f) => (
          <div key={f.id} style={{ padding: "10px 0", borderBottom: "1px solid #f0f1f3" }}>
            <p style={styles.findingTitle}>
              <span style={styles.badge(SEVERITY_COLOR[f.severity])}>{f.id} · {SEVERITY_LABEL[f.severity]}</span>
              {f.title}
            </p>
            <p style={styles.findingMeta}>{f.category}</p>
            <p style={styles.p}>{f.detail}</p>
            <p style={styles.action}>→ {f.action}</p>
          </div>
        ))}
      </div>

      <div style={styles.card}>
        <h2 style={styles.cardTitle}>值得保持的优势</h2>
        {strengths.map((s) => (
          <p key={s} style={{ ...styles.p, margin: "0 0 4px" }}>✓ {s}</p>
        ))}
      </div>

      <p style={{ fontSize: 11, color: "#9aa1ac", textAlign: "center" }}>
        详情见同目录 findings.json / canvas.json · 工作区 /Users/xalor/Documents/Maodouchat
      </p>
    </div>
  );
}
