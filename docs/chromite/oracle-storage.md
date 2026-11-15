# Oracle 테이블스페이스·데이터 파일 관리 (2026-11-15, 앱 변경 #127)

## 목적

사용자 요청 "오라클 테이블스페이스는 어떤가" → "오라클 뿐 아니고 다른 데이터베이스도 확인한다" → "추가 가능한 데이터베이스에 대해 저장소를
관리할 수 있는 기능을 추가한다". 두 조건은 Altibase 테이블스페이스 관리(#126, [altibase.md](altibase.md) "테이블스페이스 관리")와 같다:
"현재 어플리케이션의 UI UX흐름의 관점을 해치지 않게 하고 불필요한 주저리주저리 나열하는 멘트는 넣지 않는다".

전에는 Storage > Tablespaces가 조회만 됐다(속성, Files, Objects, 공간 사용량 화면). 이제 테이블스페이스를 만들고 지우고 이름을 바꾸고,
온라인·오프라인, 읽기 전용·읽기 쓰기로 바꾸고, 데이터(임시) 파일을 더하고 크기·자동 확장을 바꾸고 지운다. 모든 명령은 기존 흐름 안에 있다:
Object Explorer 메뉴 → (설정 대화상자) → 스크립트 미리 보기(취소가 기본) → 열린 트랜잭션 질문 → 실행 → 트리 갱신. 새 창 종류·마법사·설명 문단은 없다.

## 화면

| 위치 | 메뉴 항목 | 흐름 | 문장 |
|---|---|---|---|
| Storage > Tablespaces 폴더 | New Tablespace… / 새 테이블스페이스 | 이름·종류(영구·임시·실행 취소)·빅파일·데이터 파일·크기(MB)·자동 확장·증가 크기·최대 크기 대화상자 → 미리 보기 | `CREATE BIGFILE / SMALLFILE [TEMPORARY / UNDO] TABLESPACE t DATAFILE / TEMPFILE 'f' SIZE n [AUTOEXTEND ON [NEXT n] [MAXSIZE n]]` |
| 테이블스페이스 노드 | Delete(기존 명령) | 삭제 확인(확인란: 내용도 삭제, 데이터 파일도 삭제, 종속 개체도 삭제(CASCADE). 모두 꺼짐, 뒤의 둘은 내용을 고를 때만 켜짐) → 미리 보기 | `DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]` |
| 테이블스페이스 노드 | Rename(F2, 기존 명령) | 새 이름 입력 → 미리 보기 | `ALTER TABLESPACE t RENAME TO n` |
| 영구·언두 테이블스페이스 | Bring Online / Take Offline (온라인으로 전환 / 오프라인으로 전환) | 트리거 사용/사용 안 함과 같음: 지금 상태의 항목은 꺼짐, 미리 보기가 확인 | `ALTER TABLESPACE t ONLINE / OFFLINE` |
| 영구 테이블스페이스 | Make Read-Only / Make Read-Write (읽기 전용으로 전환 / 읽기 쓰기로 전환) | 같음. 읽기 전용은 온라인 읽기 쓰기일 때만, 읽기 쓰기는 읽기 전용일 때만 켜짐 | `ALTER TABLESPACE t READ ONLY / READ WRITE` |
| 스몰파일 테이블스페이스의 Files 폴더 | New Data File… / 새 데이터 파일 | 파일·크기·자동 확장 대화상자 → 미리 보기 | `ALTER TABLESPACE t ADD DATAFILE / TEMPFILE 'f' SIZE n [AUTOEXTEND …]` |
| 그 폴더의 파일 노드 | Data File Settings… / 데이터 파일 설정 | 지금 값으로 채운 대화상자(바뀐 것만) → 미리 보기 | `ALTER DATABASE DATAFILE / TEMPFILE 'f' RESIZE n`, `… AUTOEXTEND OFF / ON [NEXT n] [MAXSIZE n]`(두 문장) |
| 파일이 둘 이상인 스몰파일 테이블스페이스의 파일 노드 | Delete(기존 명령) | 삭제 확인 → 미리 보기 | `ALTER TABLESPACE t DROP DATAFILE / TEMPFILE 'f'` |

- **명령을 보이지 않는 저장소**: SYSTEM·SYSAUX(데이터베이스가 정한 이름이라 이름으로 판단), 데이터베이스 기본 영구·임시 테이블스페이스
  (`DATABASE_PROPERTIES`의 `DEFAULT_PERMANENT_TABLESPACE`·`DEFAULT_TEMP_TABLESPACE`), 활성 언두 테이블스페이스(`V$PARAMETER`의 `undo_tablespace`).
  로그인이 그 값을 읽지 못하면 그 종류(영구·임시·언두)의 테이블스페이스 모두에 새 명령을 보이지 않는다(추측하지 않음). 이런 노드에는 새 항목이
  없고 Delete·Rename은 꺼져 있다(지울 수 없는 개체의 기존 메뉴 모양). 파일 노드의 데이터 파일 설정·삭제, Files 폴더의 New Data File…도 없다.
- 종류별 지원은 카탈로그 값(`DBA_TABLESPACES.CONTENTS`, `BIGFILE`, `STATUS`)으로 정한다: 임시는 온라인·오프라인·읽기 전용이 없고, 언두는 읽기 전용이
  없고, 빅파일은 파일 추가와 파일 삭제가 없다. 파일이 하나뿐인 테이블스페이스의 파일 Delete는 꺼져 있다.
- 대화상자의 데이터 파일과 크기는 꼭 입력한다(아래 확인한 문법: 이 서버는 파일을 스스로 만들지 않는다). 증가 크기·최대 크기는 비우면 문장에서 빠진다.
  설정 대화상자에서 아무것도 바꾸지 않으면 속성 편집과 같은 "실행할 문장이 없다" 알림이다.
- 빅파일 확인란의 처음 값은 데이터베이스 기본값(`DATABASE_PROPERTIES.DEFAULT_TBS_TYPE`, 이 서버는 BIGFILE)이고, 문장에는 고른 쪽(`BIGFILE`/`SMALLFILE`)이
  늘 적힌다. 10g보다 오래된 서버(빅파일 없음)에는 확인란과 키워드가 없다.
- DDL이 세션의 트랜잭션을 커밋하므로 미리 보기 머리글에 "DDL 문을 바로 커밋" 줄이 붙는다(JDBC `dataDefinitionCausesTransactionCommit` = true, 아래 확인).
- Files 노드의 이름은 한국어 "파일" → "데이터 파일", 영어 "File" → "Data File"로 바꿨다(메뉴가 "New File… / 새 파일"로 보이지 않게, Altibase와 같은 처리).

## 구조

| 위치 | 내용 |
|---|---|
| `ext.oracle` `model/OracleTablespaceDDL`(새) | 확인한 문법으로 문장을 만드는 한 곳: 만들기, 삭제, 이름 바꾸기, 온라인·오프라인, 읽기 전용·읽기 쓰기, 파일 추가·변경·삭제. 크기는 KB(1024 배수면 `M`, 아니면 `K`) |
| `ext.oracle` `model/OracleStorageRoles`(새) | 기본 영구·임시, 활성 언두, 기본 테이블스페이스 종류를 읽는다(못 읽은 값은 null) |
| `ext.oracle` `OracleTablespace` | 새 객체 생성자, `DBPNamedObject2`(이름 바꾸기), `isFixedStorage`와 종류별 지원 판단, 행 다시 읽기(`refreshObject`가 상태도 다시 읽음), 파일 캐시 공개 |
| `ext.oracle` `OracleDataFile` | 새 파일 설정, ADD 직후 서버의 전체 경로로 행 읽기, 이름을 따옴표 없이(`DBPQualifiedObject`) |
| `ext.oracle` `OracleDataSource.TablespaceCache` | 목록을 읽을 때 `OracleStorageRoles`도 읽는다(메뉴가 DB 없이 정해지게) |
| `ext.oracle` `edit/OracleTablespaceManager`(새) | 만들기(데이터 원본 컨테이너만), 삭제(옵션 셋), 이름 바꾸기, CREATE 직후 새 행 읽기, 속성 편집 없음 |
| `ext.oracle` `edit/OracleDataFileManager`(새) | 파일 추가·삭제, 직후 새 파일 행 읽기와 테이블스페이스 크기 다시 읽기 |
| `ext.oracle` `plugin.xml`, `bundle.properties`, `bundle_ko.properties` | 관리자 두 개 등록, `tree.file.node.name` "Data File" / "데이터 파일" |
| `model.admin` `ObjectMaintenanceActions` | 동작 `TABLESPACE_READ_ONLY`, `TABLESPACE_READ_WRITE`(새), `TABLESPACE_ONLINE`·`TABLESPACE_OFFLINE`·`DATA_FILE_SETTINGS`(#126과 같은 동작)의 Oracle 분기 한 묶음 |
| `ui.swing` `OracleStorageDialogs`(새) | 세 대화상자(새 테이블스페이스, 새 데이터 파일, 데이터 파일 설정). `DesignerDialogs.showDialog` 틀 |
| `ui.swing` `NewObjectDialogs`, `ObjectMaintenanceCommands` | 대화상자 연결, 메뉴 두 항목(읽기 전용·읽기 쓰기)과 상태 판단, 실행 뒤 테이블스페이스 노드 다시 읽기 |
| `ui.swing` `CreateObjectMessages`, `ObjectMaintenanceMessages`(+`_ko`) | 화면 문구(아래 "문구") |

- 삭제 확인의 세 확인란과 의존(`ExplorerObjectCommands.DeleteOption`), 미리 보기, 열린 트랜잭션 질문, 이름 바꾸기 입력 창은 손대지 않았다(기능 비트만 켬).

## 확인한 문법 (Oracle AI Database 26ai Free 23.26.3, 127.0.0.1:1521, FREEPDB1과 CDB 루트, SYSTEM과 임시 사용자 `CHR_TS_USER`, `CHR_TS_*` 테이블스페이스만, 끝에 모두 지움)

서버 상태(읽기만): CDB(`FREE`), PDB `FREEPDB1`(로컬 언두 `UNDOTBS1`), `DB_CREATE_FILE_DEST` 없음, `DEFAULT_TBS_TYPE` = BIGFILE, 기본 영구 `USERS`, 기본 임시 `TEMP`,
데이터 파일 위치 `/opt/oracle/oradata/FREE/FREEPDB1/`(루트는 `/opt/oracle/oradata/FREE/`). 시험 기록은 `D:\Tools\_scratch\oracle-storage\evidence\p01`–`p07`.

| 문장 | 서버의 답 |
|---|---|
| `CREATE TABLESPACE x` | ORA-02199 "DATAFILE/TEMPFILE 절이 빠졌습니다" |
| `CREATE TABLESPACE x DATAFILE SIZE 4M`(파일 이름 없음) | ORA-02236 "파일명이 부적합합니다" (`DB_CREATE_FILE_DEST`가 없어 서버가 파일을 만들지 않음) |
| `… DATAFILE '/…/f.dbf'`(크기 없음, 없는 파일) | ORA-01119 / ORA-17610 "파일이 존재하지 않으며 크기가 지정되지 않음" |
| `CREATE TABLESPACE x DATAFILE 'f.dbf' SIZE 4M`(기본 빅파일) | ORA-03214 "최소 블록 784보다 작습니다"(빅파일 최소 약 6.1 MB) |
| `CREATE TABLESPACE x DATAFILE 'chr_ts_p1_01.dbf' SIZE 8M`(상대 이름) | 됨. 파일은 `$ORACLE_HOME/dbs/chr_ts_p1_01.dbf`(`/opt/oracle/product/26ai/dbhomeFree/dbs/`), 빅파일 |
| `CREATE TABLESPACE x DATAFILE '/…/f.dbf' SIZE 8M AUTOEXTEND ON NEXT 1M MAXSIZE 16M` | 됨(INCREMENT_BY 128 블록, MAXBYTES 16777216) |
| `CREATE BIGFILE TABLESPACE … SIZE 8M AUTOEXTEND ON NEXT 1M MAXSIZE UNLIMITED` | 됨(MAXBYTES 35184372064256) |
| `CREATE SMALLFILE TABLESPACE … SIZE 2M AUTOEXTEND ON` / `SIZE 2048K AUTOEXTEND OFF` / `SIZE 64K` | 됨(NEXT 1 블록, MAX 34359721984) / 됨 / ORA-03214 "최소 블록 11" |
| `CREATE SMALLFILE TABLESPACE … DATAFILE 'a' SIZE 1M, 'b' SIZE 1M` / `CREATE BIGFILE … 'a' …, 'b' …` | 됨 / ORA-32774 "bigfile 테이블스페이스에 대해 둘 이상의 파일" |
| `… SIZE 4M AUTOEXTEND ON NEXT 1M MAXSIZE 2M` | ORA-02494 "MAXSIZE … 부적당" |
| 같은 이름 / 이미 쓰는 파일 | ORA-01543 "테이블스페이스는 이미 존재합니다" / ORA-01537 "파일이 이미 데이터베이스에 속합니다" |
| `CREATE [SMALLFILE] TEMPORARY TABLESPACE t TEMPFILE '…' SIZE 4M [AUTOEXTEND ON NEXT 1M MAXSIZE 16M]` | 됨(기본 빅파일; 스몰파일은 2M도 됨) |
| `CREATE TEMPORARY TABLESPACE t DATAFILE …` | ORA-25139 |
| `CREATE [SMALLFILE] UNDO TABLESPACE u DATAFILE '…' SIZE 4M [AUTOEXTEND …]` / `… TEMPFILE …` | 됨 / ORA-02180 |
| `CREATE TABLESPACE "chr_ts_q1" …` | 됨(소문자 이름 보존) |
| `ALTER TABLESPACE x RENAME TO y`(영구·임시·언두, 따옴표 이름, 읽기 전용) | 됨. 있는 이름으로: ORA-02154. 오프라인 테이블스페이스: ORA-01135 |
| `ALTER TABLESPACE x OFFLINE` / `ONLINE` / `OFFLINE NORMAL` | 됨(`STATUS` OFFLINE, 파일 `ONLINE_STATUS` OFFLINE). 다시 OFFLINE: ORA-01539. 온라인에 다시 ONLINE: 됨(오류 없음) |
| 임시 OFFLINE / READ ONLY | ORA-03217 "TEMPORARY TABLESPACE의 변경하기 위한 옵션이 부적당" |
| 활성이 아닌 언두 OFFLINE / ONLINE / READ ONLY | 됨 / 됨 / ORA-30021 "실행 취소 테이블스페이스에 허용되지 않는 작업" |
| `ALTER TABLESPACE x READ ONLY` / `READ WRITE` | 됨(`STATUS` READ ONLY). 다시: ORA-01644 / ORA-01646. 오프라인(읽기 쓰기)에: ORA-01539 / ORA-01646 |
| 읽기 전용에 OFFLINE | 됨, 그런데 `DBA_TABLESPACES.STATUS`는 계속 READ ONLY(파일 `ONLINE_STATUS`만 OFFLINE). 그 상태에서 READ WRITE: ORA-01135 |
| 읽기 전용 / 오프라인에 파일 추가 | ORA-01641 / ORA-01539 |
| `ALTER TABLESPACE s ADD DATAFILE '…' SIZE 1M [AUTOEXTEND ON NEXT 512K MAXSIZE 8M]`(스몰파일 영구·언두) | 됨. 같은 파일: ORA-01537. 크기 없음: ORA-01119/ORA-17610 |
| 빅파일에 ADD DATAFILE / TEMPFILE | ORA-32771 "bigfile 테이블스페이스에 파일을 추가할 수 없음" |
| 임시에 `ADD TEMPFILE '…' SIZE 2M …` / `ADD DATAFILE` / 영구에 `ADD TEMPFILE` | 됨(1M은 ORA-03214 "최소 블록 131") / ORA-03217 / ORA-01268 |
| `ALTER DATABASE DATAFILE 'f' RESIZE 3M`(늘리기·줄이기), `ALTER DATABASE DATAFILE 21 RESIZE 4M`, `ALTER DATABASE TEMPFILE 'f' RESIZE 3M` | 됨. 너무 작게: ORA-03214 |
| `… RESIZE 3M AUTOEXTEND ON` | ORA-03049(한 문장에 둘 다 안 됨) |
| `… AUTOEXTEND ON NEXT 1M MAXSIZE 16M` / `ON MAXSIZE 20M` / `ON` / `ON NEXT 2M MAXSIZE UNLIMITED` / `OFF` | 모두 됨. NEXT·MAXSIZE를 빼면 지금 값이 그대로 남음 |
| 데이터 파일에 `ALTER DATABASE TEMPFILE` / 임시 파일에 `ALTER DATABASE DATAFILE` | ORA-01516 |
| 읽기 전용의 파일 RESIZE / AUTOEXTEND, 오프라인의 파일 RESIZE / AUTOEXTEND | ORA-02495 / ORA-00376 |
| `ALTER TABLESPACE P5 RESIZE 10M`, `… AUTOEXTEND ON NEXT 2M MAXSIZE 32M`(빅파일) / 스몰파일에 `RESIZE` | 됨 / ORA-32773 (그래서 파일 설정은 두 종류 모두 `ALTER DATABASE DATAFILE`을 씀) |
| `ALTER TABLESPACE s DROP DATAFILE '…'`(빈 파일, 익스텐트가 있는 테이블의 테이블스페이스에서 새로 더한 파일) | 됨. **파일도 서버 디스크에서 지워짐**(같은 이름을 REUSE 없이 다시 추가할 수 있었음) |
| 첫 파일 DROP | ORA-03263 "첫번째 파일을 삭제할 수 없습니다"(가장 작은 FILE_ID였지만 카탈로그에 "첫 파일" 표시가 없음) |
| 파일이 하나뿐 / 빅파일 파일 DROP | ORA-03261 "하나의 파일만 있습니다" |
| 임시 파일이 둘일 때 첫 임시 파일 DROP | 됨 |
| `DROP TABLESPACE s`(개체 있음) | ORA-01549 "INCLUDING CONTENTS 옵션을 사용해 주십시오" |
| `DROP TABLESPACE s CASCADE CONSTRAINTS` / `s AND DATAFILES` / `s INCLUDING CONTENTS CASCADE CONSTRAINTS AND DATAFILES` | 모두 ORA-02173 |
| `DROP TABLESPACE s INCLUDING CONTENTS [AND DATAFILES]`(다른 테이블스페이스의 외래 키가 참조) | ORA-02449 |
| `DROP TABLESPACE s INCLUDING CONTENTS AND DATAFILES CASCADE CONSTRAINTS` | 됨(외래 키 제약도 지워짐) |
| `DROP TABLESPACE s INCLUDING CONTENTS KEEP DATAFILES` 뒤 같은 파일로 `CREATE … REUSE` | 됨(파일은 남음; 남은 파일은 REUSE로 다시 만들어 `AND DATAFILES`로 지움) |
| 권한 없는 사용자(`CREATE SESSION`만) | 만들기·OFFLINE·RENAME·ADD DATAFILE·RESIZE·DROP 모두 ORA-01031. `DBA_TABLESPACES`·`DBA_DATA_FILES`·`V$PARAMETER`는 ORA-00942, `USER_TABLESPACES`는 빈 목록(할당량 없음), `DATABASE_PROPERTIES`는 읽힘 |
| 트랜잭션(수동 커밋 세션) | INSERT 뒤 성공한 `ALTER TABLESPACE … READ ONLY`, 실패한(ORA-01644, ORA-01543) 문장 모두 INSERT를 커밋함(롤백해도 행이 남음). 구문 오류(ORA-02180)는 커밋하지 않음. JDBC `dataDefinitionCausesTransactionCommit` = true |
| CDB 루트(SYSTEM@FREE) | 만들기·파일 추가·RESIZE·AUTOEXTEND·파일 DROP·OFFLINE/ONLINE·READ ONLY/WRITE·RENAME·DROP 모두 PDB와 같음. 루트에서 PDB 파일 이름: ORA-01516 |

## 설계 결정

- **기존 흐름만.** 만들기는 폴더의 New &lt;종류&gt;…(대화상자 길), 삭제·이름 바꾸기는 기존 Delete·F2, 상태·설정 명령은 유지 관리 명령(#126과 같은 메뉴 자리, 같은
  미리 보기·트랜잭션 질문·격리 실행)이다. 온라인·오프라인과 데이터 파일 설정은 #126의 동작과 메뉴 항목을 그대로 쓰고, 읽기 전용·읽기 쓰기 두 항목만 더했다.
- **명령이 없는 저장소는 카탈로그로.** 이름으로 정하는 것은 SYSTEM·SYSAUX뿐이다. 기본·활성 언두는 목록을 읽을 때 같은 세션에서 읽고(메뉴는 DB를 읽지 않고
  정해야 함), 못 읽으면 그 종류 전부를 막는다. 기본 임시가 테이블스페이스 그룹 이름이면 그룹의 테이블스페이스는 막지 않는다(서버가 마지막 구성원 삭제를 거절함, 시험하지 않음).
- **삭제 옵션 셋, 모두 꺼짐.** 서버 문법이 그대로 확인란이 된다. 파일과 CASCADE는 내용과 함께일 때만 문법이 되므로 내용을 고를 때만 켜진다(#126과 같은 의존).
  `KEEP DATAFILES`는 확인란이 없다: 끈 "데이터 파일도 삭제"와 결과가 같다(파일이 남음).
- **상태 항목.** `STATUS`가 ONLINE이면 온라인으로 전환·읽기 쓰기로 전환이 꺼지고, OFFLINE이면 오프라인으로 전환·읽기 전용·읽기 쓰기가 꺼진다(서버가 거절함).
  READ ONLY는 오프라인인지 카탈로그 상태만으로 알 수 없어 온라인·오프라인이 둘 다 켜져 있다(온라인에 ONLINE은 오류 없이 지나감).
- **파일 삭제.** 빅파일·파일 하나는 꺼짐(ORA-03261). 스몰파일의 첫 파일은 서버가 ORA-03263으로 거절하지만 어느 파일이 첫 파일인지 카탈로그 열이 없어
  (FILE_ID는 다시 쓰임) 막지 않고 서버 오류를 보인다.
- **새 파일 이름.** 서버가 파일을 스스로 만들지 않으므로(`DB_CREATE_FILE_DEST` 없음) 파일 칸을 비울 수 없다. 서버가 OMF를 쓰는 경우의 빈 파일 이름은 시험할 수
  없어(서버 매개변수를 바꾸지 않음) 넣지 않았다. 상대 이름은 서버의 `$ORACLE_HOME/dbs`에 만들어진다(서버 동작, 문구로 늘어놓지 않음). ADD 직후 서버가 붙인
  전체 경로를 입력한 이름으로 찾아 노드 이름으로 쓴다.
- **파일 설정은 `ALTER DATABASE DATAFILE/TEMPFILE`.** 빅파일의 `ALTER TABLESPACE … RESIZE`는 스몰파일에서 거절되므로 두 종류 모두 같은 문장을 쓴다. 크기와 자동 확장은
  한 문장에 쓸 수 없어 두 문장이다. NEXT·MAXSIZE 칸을 비우면 그 절을 쓰지 않는다(서버가 지금 값을 둠).
- **속성 편집 없음.** 모델 속성은 `editable`이지만 ALTER를 만드는 곳이 없어 관리자의 `canEditObject`가 거짓이다(속성 창은 그대로 읽기 전용).
- **ORACLE LICENSE GUARD.** 쓰는 뷰는 `DBA_TABLESPACES`/`USER_TABLESPACES`, `DBA_DATA_FILES`, `DBA_TEMP_FILES`, `DATABASE_PROPERTIES`, `V$PARAMETER`뿐이다(팩 불필요).
- **PDB와 루트.** 연결한 컨테이너의 테이블스페이스만 보이고(`DBA_*`), 명령도 그 컨테이너에서 실행된다. 루트와 PDB에서 같은 문장이 같게 동작했다. 루트의
  SYSTEM·SYSAUX·기본·활성 언두도 같은 규칙으로 막힌다.
- **권한이 모자란 로그인.** `DBA_TABLESPACES`를 못 읽으면 기존 트리처럼 `USER_TABLESPACES`(할당량이 있는 것)만 보인다. New Tablespace…는 폴더에 보이고 실행하면
  서버의 ORA-01031이 실패한 변경으로 보인다(Altibase와 같음). `V$PARAMETER`를 못 읽으므로 언두 테이블스페이스에는 명령이 없다.

## 확인 (2026-11-15, 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 연결은 암호를 저장하지 않음)

기록: `D:\Tools\_scratch\oracle-storage\run\s01`–`s15`(앱 안 단계와 결과), `evidence\a01`–`a04`(카탈로그), 캡처 `sheets\captures\`.

| 명령 | 미리 보기의 문장 | 서버 결과(`DBA_TABLESPACES`·`DBA_DATA_FILES`·`DBA_TEMP_FILES`) | 트리 |
|---|---|---|---|
| New Tablespace… 영구 스몰파일 | `CREATE SMALLFILE TABLESPACE CHR_TS_APP_P1 DATAFILE '/…/chr_ts_app_p1_01.dbf' SIZE 2M AUTOEXTEND ON NEXT 1M MAXSIZE 16M` | PERMANENT, BIGFILE NO, 2097152 / 16777216 / 128 블록 | 노드 생김 |
| 영구 빅파일(상대 이름) / 임시 / 언두 | `CREATE BIGFILE TABLESPACE CHR_TS_APP_B1 DATAFILE 'chr_ts_app_b1_01.dbf' SIZE 8M`, `CREATE SMALLFILE TEMPORARY … TEMPFILE '…' SIZE 2M`, `CREATE SMALLFILE UNDO … SIZE 2M` | 빅파일 YES, 파일 `$ORACLE_HOME/dbs/chr_ts_app_b1_01.dbf` / TEMPORARY / UNDO | 노드 생김, Files에 서버의 전체 경로 |
| 대화상자에서 취소 / 미리 보기에서 취소 | — / 문장만 보임 | 바뀌지 않음(P1은 한 번만 만들어짐) | 바뀌지 않음 |
| 빈 이름, 파일 없음, 크기 없음·0.01 MB | 대화상자 오류 줄 "Enter a name." / "Enter the data file name (without quotes)." / "Enter the sizes in MB." | — | — |
| 같은 이름 | 미리 보기 뒤 실행 | 모델의 확인 "Object 'CHR_TS_APP_T1' already exists"(서버에 가기 전, 기존 공통 동작) | 바뀌지 않음 |
| New Data File…(상대 이름, 자동 확장) | `ALTER TABLESPACE CHR_TS_APP_P1 ADD DATAFILE 'chr_ts_app_p1_02.dbf' SIZE 1M AUTOEXTEND ON NEXT 512K MAXSIZE 8M` | 파일 `…/dbs/chr_ts_app_p1_02.dbf`, 1048576 / 8388608 / 64 | 파일 노드(서버의 전체 경로) |
| New Data File…(임시) | `ALTER TABLESPACE CHR_TS_APP_T1 ADD TEMPFILE '…/chr_ts_app_t1_02.dbf' SIZE 2M` | 임시 파일 추가 | 노드 생김 |
| Data File Settings…(바꾸지 않고 OK) | 없음 | — | "This change produces no SQL statement…" 알림 |
| Data File Settings…(크기+자동 확장, 자동 확장 끄기, 임시 파일) | `ALTER DATABASE DATAFILE '…' RESIZE 3M` + `… AUTOEXTEND ON NEXT 1M MAXSIZE 10M`, `… AUTOEXTEND OFF`, `ALTER DATABASE TEMPFILE '…' RESIZE 3M` + `… AUTOEXTEND ON NEXT 1M MAXSIZE 8M` | 3145728 / 10485760 / 128, 그 뒤 NO | 다시 열면 새 값(처음 초점은 크기 칸) |
| Data File Settings…(64K, 실패) | `… RESIZE 64K` | "The command failed on …" + ORA-03214 오류 창 | 바뀌지 않음 |
| Delete 파일(둘 중 하나) | `ALTER TABLESPACE CHR_TS_APP_P1 DROP DATAFILE '…'` | 행 없어짐 | 노드 빠짐, 남은 하나의 Delete는 꺼짐 |
| Take Offline / Bring Online | `ALTER TABLESPACE CHR_TS_APP_P1 OFFLINE / ONLINE` | OFFLINE / ONLINE | 오프라인이면 온라인만 켜짐(읽기 전용·읽기 쓰기 꺼짐) |
| Make Read-Only / Make Read-Write | `… READ ONLY / READ WRITE` | READ ONLY / ONLINE | 읽기 전용이면 읽기 쓰기와 온라인·오프라인이 켜짐 |
| Rename(F2, 언두) | `ALTER TABLESPACE CHR_TS_APP_U1 RENAME TO CHR_TS_APP_U1R` | 이름 바뀜 | 노드 이름 바뀜 |
| Delete 테이블스페이스(옵션 없음, 테이블 있음) | `DROP TABLESPACE CHR_TS_APP_P1` | ORA-01549 오류 창 | 바뀌지 않음 |
| Delete(내용만, 다른 테이블스페이스의 외래 키) | `… INCLUDING CONTENTS` | ORA-02449 오류 창 | 바뀌지 않음 |
| Delete(내용+파일+CASCADE) | `… INCLUDING CONTENTS AND DATAFILES CASCADE CONSTRAINTS` | 지워짐, 테이블과 외래 키도 | 노드 빠짐 |
| 권한 없는 사용자(`CHR_TS_USER`, CREATE SESSION) | `CREATE BIGFILE TABLESPACE CHR_TS_APP_X1 …` | ORA-01031 오류 창 | Tablespaces는 빈 폴더(`USER_TABLESPACES`), New Tablespace…만 보임 |
| 읽기 전용 연결 | — | New Tablespace…, Take Offline, Delete 모두 "ORA does not allow changing database objects…" | — |
| CDB 루트 연결(`FREE`) | `CREATE SMALLFILE TABLESPACE CHR_TS_APP_R1 DATAFILE '/opt/oracle/oradata/FREE/chr_ts_app_r1_01.dbf' SIZE 2M`, `… READ ONLY`, `DROP … INCLUDING CONTENTS AND DATAFILES` | 루트에서 만들어지고 지워짐 | 루트의 SYSTEM·SYSAUX·TEMP·UNDOTBS1·USERS는 새 항목 없음, Rename·Delete 꺼짐 |

- 메뉴: SYSTEM·SYSAUX·TEMP·UNDOTBS1·USERS(기본·활성 언두) 노드에는 새 항목이 없고 Rename·Delete가 꺼짐. 임시는 Rename·Delete만, 언두는 온라인·오프라인까지,
  빅파일은 Files 폴더에 New Data File…이 없고 파일 노드는 데이터 파일 설정만(Delete 꺼짐).
- 열린 트랜잭션(수동 커밋 연결 `ORA TX`, 쿼리 창의 `INSERT`): Take Offline → "Uncommitted Transactions" 질문(Commit / Roll Back / Cancel, 취소가 기본).
  취소 → 아무것도 하지 않음(ONLINE, 다른 세션에서 행 없음), 롤백 → INSERT 없어지고 오프라인, 커밋 → INSERT가 남고 오프라인(`a03`, `a04`).
- 미리 보기 머리글에 "This database commits each DDL statement at once; the change cannot be rolled back." 줄이 붙는다.
- 연결 Refresh 뒤, 연결 끊고 다시 연결한 뒤에도 메뉴가 같다(CHR_TS_APP_B1: 온라인 꺼짐·오프라인·읽기 전용 켜짐, USERS·UNDOTBS1: 새 항목 없음).
- 같은 빌드에서 Oracle 테이블 이름 바꾸기(`ALTER TABLE CHR_TS_USER.CHR_TS_SMOKE RENAME TO CHR_TS_SMOKE2`)와 삭제(확인란은 CASCADE 하나)가 전과 같고,
  Altibase 시험 서버(#291)에서 Tablespaces 폴더의 New Tablespace…(종류 Memory/Disk/Temporary/Volatile, 미리 보기 `CREATE TABLESPACE … DATAFILE … SIZE 4M`에서 취소),
  시스템 테이블스페이스 노드의 메뉴(새 항목 없음, Delete 꺼짐)가 전과 같다. Altibase에서는 아무것도 실행하지 않았다.
- 화면: 영어·한국어, 라이트·다크, 100 %·150 %에서 폴더·노드·파일 메뉴, 세 대화상자와 이름 바꾸기, 미리 보기, 삭제 확인, 트리를 캡처했고, origin/devel 빌드와 메뉴를 비교한 시트를 만들었다.
- 시험 서버 정리: 테이블스페이스는 모두 `INCLUDING CONTENTS AND DATAFILES`로, 임시 사용자 `CHR_TS_USER`는 CASCADE로 지웠다. 끝에 `DBA_TABLESPACES`·`DBA_DATA_FILES`·`DBA_TEMP_FILES`·
  `DBA_USERS`(PDB)와 `CDB_DATA_FILES`·`CDB_TEMP_FILES`·`CDB_USERS`(루트)에 `CHR` 행이 없다(`evidence\p20-cleanup.txt`). `KEEP DATAFILES`로 남긴 파일 하나는 `REUSE`로 다시 만들어 지웠다.
- 회귀 테스트는 작성·실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: Oracle 테이블스페이스 노드 새로 고침(이제 행을 다시 읽음), Oracle 테이블스페이스 목록 읽기(질의 두 개가 더해짐),
  유지 관리 메뉴, Altibase 데이터 파일 설정 툴팁.

## upstream 대비 변경점

- upstream은 Oracle 테이블스페이스를 조회만 한다. 객체 관리자 두 개, 문장 클래스, 저장소 역할 읽기, 모델의 새 객체·다시 읽기·이름 바꾸기를 더했다.

## 변경 이력

- 2026-11-15 최초 작성(앱 변경 #127): 만들기·삭제·이름 바꾸기·온라인/오프라인·읽기 전용/읽기 쓰기·데이터 파일 추가·설정·삭제, 확인한 문법.
