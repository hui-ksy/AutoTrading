---
name: auto-go
version: "1.0.0"
description: SPEC 기반 코드 구현 — TDD/DDD 방법론을 따라 안전하게 구현합니다
platform: gemini-cli
project: AutoTrading
mode: full
triggers:
  - "implement"
  - "go"
  - "구현"
---

# auto-go — SPEC 구현

**프로젝트**: AutoTrading | **모드**: full

## 설명

SPEC 문서를 기반으로 코드를 구현합니다.

## 사용법

```
@auto-go SPEC-ID
```

## 구현 절차


**TDD 사이클**:
1. RED — 실패 테스트 작성
2. GREEN — 최소 구현
3. REFACTOR — 코드 개선


## 품질 기준

- 커버리지: 85%+
- LSP 에러: 0 / 타입 에러: 0 / 린트: 0

## Subagent Delegation

When a task modifies 3+ files, exceeds 200 lines, or spans multiple domains, delegate to specialized agents using `@agent`.

### Executor Agent

```
@executor Implement {task description}
  Context: .autopus/specs/{SPEC-ID}/spec.md, .autopus/specs/{SPEC-ID}/plan.md
  Constraint: File size limit 300 lines per source file
```

### Tester Agent

```
@tester Write tests for {scope}
  Context: .autopus/specs/{SPEC-ID}/acceptance.md
  Coverage target: 85%+
```

### Reviewer Agent

```
@reviewer Review implementation for {SPEC-ID}
  Criteria: TRUST-5
```

### Delegation Rules

- Define clear scope with expected output for each agent
- Include SPEC context files in the agent prompt
- Review agent output before integrating
- Use parallel delegation for independent subtasks
- Maximum 3 sequential agent chains

## Multi-Provider 모드

`--multi` 플래그로 멀티 프로바이더 오케스트레이션을 활성화합니다:

```bash
auto orchestra run "{review topic}" --strategy consensus --rounds fast
```

서브프로세스 모드가 자동 적용되며, 여러 AI 프로바이더의 합의 기반 리뷰를 수행합니다.

## Branding Formats

### Pipeline Progress (show at Phase transitions)

```
🐙 Pipeline ─────────────────────────
  ✓ Phase 1: Planning
  → Phase 2: Implementation [N/M tasks]
  ○ Phase 3: Testing
  ○ Phase 4: Review
```

Status symbols: `✓` completed, `→` active, `○` pending. Replace `[N/M tasks]` with actual counts.

### Workflow Lifecycle (show after go completes)

```
🐙 Workflow: {SPEC-ID}
  ✓ plan  →  ● go  →  ○ sync
```

### Agent Result Format (use for executor/tester subagent results)

executor:
```
🐙 executor ─────────────────────────
  파일: {N}개 수정 | 테스트: {N}개 추가 | 커버리지: {N}%
  다음: 검증 단계로 진행
```

tester:
```
🐙 tester ───────────────────────────
  테스트: {N}개 추가 | 커버리지: {before}% → {after}% | 통과: {N}/{N}
  다음: 리뷰 단계로 진행
```

### Error Recovery (show on failures)

Validation failure:
```
✗ Validation 실패: {N}개 이슈 발견
  복구 옵션:
  1. /auto go {SPEC-ID} --continue  (중단점에서 재개)
  2. /auto fix "{specific issue}"   (개별 이슈 수정)
```

Subagent failure:
```
✗ {agent-name} 실패: {error description}
  복구 옵션:
  1. /auto go {SPEC-ID} --continue  (파이프라인 재개)
  2. {manual fallback instruction}  (수동 처리)
```
