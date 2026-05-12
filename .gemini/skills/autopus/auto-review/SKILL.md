---
name: auto-review
version: "1.0.0"
description: 코드 리뷰 — TRUST 5 기준으로 변경사항을 검토합니다
platform: gemini-cli
project: AutoTrading
mode: full
triggers:
  - "review"
  - "리뷰"
  - "검토"
---

# auto-review — 코드 리뷰

**프로젝트**: AutoTrading | **모드**: full

## 설명

TRUST 5 기준으로 코드를 리뷰합니다.

## 사용법

```
@auto-review
@auto-review path/to/file
```

## TRUST 5

| 기준 | 설명 |
|------|------|
| Tested | 커버리지 85%+ |
| Readable | 명확한 네이밍, 린트 클린 |
| Understandable | 문서화, 적절한 복잡도 |
| Secured | OWASP 준수, 입력 검증 |
| Trackable | Conventional Commits |

## Branding Formats

### Agent Result Format (use for reviewer subagent result)

```
🐙 reviewer ─────────────────────────
  Verdict: {APPROVE|REQUEST_CHANGES} | Issues: {N}개 | 심각도: {low|medium|high}
  다음: {verdict-based guidance}
```

### Next Step Auto-Detection (show after review completes)

```
다음 단계: {recommendation}
```

Detection order:
1. Verdict `APPROVE` + SPEC status `implemented` → `✓ 구현 완료 → /auto sync {SPEC-ID}`
2. Verdict `REQUEST_CHANGES` → recommend `/auto go {SPEC-ID} --continue` to address issues
3. SPEC status `completed` → `🐙 {SPEC-ID} 완료. 다음 기능: /auto plan "기능 설명"`
