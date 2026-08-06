package com.xxx.ragdoc.application.chat.sufficiency;

/**
 * PR-7b / EMS-PR7 §6.1: Sufficiency Judge 接口。
 *
 * <p>实现链:
 *
 * <ol>
 *   <li>{@code RuleSufficiencyJudge} — 确定性规则 (文本/字段/version 一致性); 规则可判时不调 Model
 *   <li>Rule 无法判定 + allowModelFallback=true → {@code ModelSufficiencyJudge} (ChatClient JSON)
 *   <li>Rule 无法判定 + allowModelFallback=false → UNDETERMINED
 * </ol>
 *
 * <p>Judge <b>不</b>调 Tool / <b>不</b>修改 Plan / <b>不</b>写 Trace / 仅输出判定 + 建议。
 */
public interface EvidenceSufficiencyJudge {

    SufficiencyDecision evaluate(SufficiencyRequest request);
}
