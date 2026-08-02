# ADR-0007: V3 部署用 k3s on Autodl GPU(替代 SSH 隧道单机方案)

- Status: Accepted
- Date: 2026-08-02

## Context

V2 阶段 Autodl GPU 用法: SSH 隧道把容器 6006 转到本地 — 只解决 reranker-on-GPU,
没把 Autodl 当成"真部署目标"。V3 DoD-1 / DoD-3 (kill -9 + 100 并发压测) 都需要真实
生产-like 部署:
- docker-compose 跑不出 Pod 重启 / HPA 横向扩容等生产特性
- 长期 SSH 隧道管理多个服务不扩展
- K8s 是简历项目名副其实的"企业级架构"硬资产

Autodl 实例能力(M4 测过):
- Ubuntu 22.04 + nvidia-container-toolkit 预装?
- 单实例 24G 显存 RTX 3090
- Autodl 自定义服务给一个公网 url(默认抓容器 6006)
- 用户态 root, 装什么都可以

候选方案:

| 候选 | 优点 | 缺点 |
|---|---|---|
| kind (Kubernetes in Docker) | 在本机 Docker 跑 K8s, 不需 Autodl | 单节点假装集群; GPU 接入要 NVIDIA device plugin 麻烦; 本机 M4 性能拉胯 |
| k3s on Autodl | 单二进制轻量级 K8s, 真集群 kubelet + containerd, GPU 节点原生支持 | 单节点(除非开多台 Autodl 组集群做演示) |
| k8s full (kubeadm) | 完整生产级 | 安装 1 天起, V3 工时不允许 |
| docker-compose only | 简单 | 演不出 K8s 4 件套(Deployment/HPA/Probe/Ingress), DoD-1 演练不够企业级 |

## Decision

V3 部署目标: **k3s 单节点 on Autodl GPU 实例**, 用 Helm chart 部署 3 服务。

- **k3s**: 比 kind 更"真生产" — kubelet + containerd 跑在真 Linux 不是容器套娃
- **Helm chart**: V3 真正交付物(没 Argo CD — 推 V4 治理)
- **GPU node selector**: reranker pod 用 `nvidia.com/gpu: 1` 资源请求 + nodeSelector
- **Ingress**: nginx-ingress 单入口, 配 Autodl 自定义服务暴露公网
- **镜像仓库**: GitHub Container Registry (GHCR), GitHub Actions push, k3s pull

V3 第 4 周工时分配:
- Day 1-2: Autodl 装 k3s + nvidia device plugin + 自验证 pod 起 GPU 容器
- Day 3-4: 3 个服务的 Helm chart + Deployment / Service / ConfigMap / HPA / Probe
- Day 5: nginx-ingress + Autodl 自定义服务通公网 + 一次端到端 deploy 验收

## Alternatives Considered

| 方案 | 取舍 |
|---|---|
| kind 单节点 | 不那么"真", GPU plugin 麻烦; 演示价值弱于 k3s |
| 多节点 k3s (2-3 台 Autodl) | 真集群演示价值更高, 但 Autodl 多实例按时计费成本翻倍, V4 视真实压力再加 |
| 完整 K8s (kubeadm) | 太重, V3 工时不够 |
| docker-compose | 跑不出 K8s 硬指标的演示价值 |

## Consequences

**正面**:
+ V3 验收现场可以演示 `kubectl get pods -o wide` + 真实 Deployment 重启, 远好于 docker-compose
+ Argo CD 留 V4 加入, V3 → V4 升级路径平滑(Helm chart 已就位)
+ Reranker 走 K8s Service ClusterIP 不再需要 SSH 隧道(拓扑干净)

**负面**:
- Autodl 单实例限制: 1 master+worker 同机, HPA 演示只能"假装"扩容(K8s 报告 pod scheduled 但其实同一节点跑)
- 公网入口仍走 Autodl "自定义服务"单一端口 → Ingress 里 Service 类型只能 ClusterIP
- Autodl 按时计费, V3 第 4 周需较长时间开着, 成本 ¥10-20/h × 30h ≈ ¥300-600

**缓解**:
- HPA 在 V3 验收时只做"配置 + 资源 limit 演示", 不强求真扩容(V4 多节点时再演示)
- Autodl 计费: 不压测就关机, 仅部署/验收期开

## Revisit

V3 完成后:
- 若客户/面试场景明确要求"真横向扩容"(如 50 并发 +) → V4 升级到 2-3 节点 k3s 集群
- 若 Argo CD / GitOps 演示价值明显 → V4 加 Argo CD + 独立 manifest repo
- 若 Longhorn / 持久化备份成为痛点 → V4 评估加 PVC backup
