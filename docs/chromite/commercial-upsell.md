# 상용판 홍보 제거

## 목적

DBeaver Community에는 DBeaver Corp의 상용판(PRO, Lite, Enterprise, Ultimate, Team Edition, CloudBeaver)을
홍보하거나 구매를 권하는 기능과 문구가 들어 있다. Chromite는 DBeaver의 상용판과 관계가 없는 독립 제품이므로
이런 기능과 문구를 모두 없앤다.

## 결정 사항 (2026-11-06 사용자 결정)

1. DBeaver 상용판 홍보는 앱에서 모두 완전히 제거한다.
2. DBeaver의 다른 제품이나 판을 가리키는 화면 문구에는 "DBeaver"라는 단어를 남기지 않는다.
   문구가 필요한 기능이면 "다른 판(edition)의 제품" 같은 일반적인 표현으로 바꾸고, 모든 로캘에서 같은 뜻으로 옮긴다.
   올바른 번역을 쓸 수 없는 로캘에는 "DBeaver"를 남기지 않고 영어 문구를 넣는다.

## 제거한 항목

### "Try DBeaver PRO" 명령 (Help 메뉴)

`plugins/org.jkiss.dbeaver.ui.app.standalone`

- `plugin.xml`: 명령 `org.jkiss.dbeaver.core.try.pro`, 핸들러, Help 메뉴 기여(`commercial` 구분선 포함),
  이 메뉴의 `visibleWhen`에만 쓰던 property tester(`org.jkiss.dbeaver.ui.app.standalone.menu.configuration`,
  속성 `isCommunity`)를 지웠다.
- 클래스 `actions.TryPROHandler`(https://dbeaver.com/download/ 를 여는 핸들러)와 `actions.MenuConfigurationPropertyTester`를 지웠다.
- `OSGI-INF/l10n/bundle*.properties`의 `command.org.jkiss.dbeaver.ui.try.pro.name`/`.description`을 모든 로캘에서 지웠다.

### 오늘의 팁 (Tip of the Day)

`plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/tipoftheday/tips.xml`

- `product` 속성이 붙은 팁 65개 중 64개를 지웠다. 모두 상용판(PRO, Lite, Enterprise, Ultimate, Team Edition,
  CloudBeaver)을 홍보하거나 상용판(`le`, `ee`, `ue`, `te`)에서만 보이던 팁이다.
- 남긴 1개는 `product="ce"`인 ER 다이어그램 표기법(Crow's Foot) 팁이다. 상용판 홍보가 아닌 실제 기능 안내다.
- 일반 팁 1개(필터 기록)에서 "If you use DBeaver PRO, the history will be saved even after restarting the app." 문장을 지웠다.
- `TipsXmlHandler`의 `product` 필터(제품 속성 `appEdition`=`ce`와 비교)는 남은 `product="ce"` 팁이 쓰므로 그대로 둔다.
  `tips.xsd`의 `productList` 패턴(`ce|le|ee|ue|te`)도 스키마 정의라서 고치지 않았다.

## 변경 이력

- 2026-11-06 Help 메뉴의 "Try DBeaver PRO" 명령과 그 명령에만 쓰던 핸들러, property tester, 메시지 키를 제거.
- 2026-11-06 오늘의 팁에서 상용판 홍보 팁 64개와 일반 팁 1개의 PRO 홍보 문장을 제거.
