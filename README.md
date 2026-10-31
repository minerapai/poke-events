# Poke Events (이벤트)

이로치 확률 증가, 대량발생, 전설 이벤트를 여는 모드입니다. 화면 위 보스바에 진행 중인 이벤트가 표시됩니다.

- 버전: 1.5.2
- Minecraft 1.21.1 / Fabric
- 필요한 모드: Cobblemon, Spawn Filter, Fabric API
- 설치: 서버에만 넣으면 됩니다. 접속하는 사람(클라이언트)은 이 모드가 없어도 됩니다.

## 다운로드

**[⬇ poke-events-1.5.2.jar 다운로드](https://github.com/minerapai/poke-events/releases/download/v1.5.2/poke-events-1.5.2.jar)**

받은 jar 파일을 마인크래프트(또는 서버) 폴더의 `mods` 폴더에 넣으면 됩니다. 다른 버전은 [Releases](https://github.com/minerapai/poke-events/releases) 에 있습니다.

## 기능

- 이로치 이벤트: 일정 시간 이로치 확률 증가
- 대량발생: 특정 포켓몬이 많이 나옴
- 전설 이벤트
- 주간 일정에 따라 자동으로 시작 (서버에 플레이어가 한 명도 없으면 열리지 않음)

## 사용법

| 명령어 | 설명 |
|---|---|
| `/pokeevent (/이벤트)` | 진행 중인 이벤트와 정기 일정 보기 |
| `/pokeevent shiny | outbreak | legend ...` | (OP) 이벤트 시작 |
| `/pokeevent stop` | (OP) 이벤트 종료 |
| `/pokeevent settings` | (OP) 이벤트 설정 보기 (클릭하면 바꾸는 명령어가 입력됨) |
| `/pokeevent species add|remove <포켓몬>` | (OP) 대량발생 후보 목록 바꾸기 |
| `/pokeevent exclude add|remove <포켓몬>` | (OP) 제외 목록 바꾸기 |
| `/pokeevent reload` | (OP) 이벤트 일정 다시 읽기 |

(OP) 표시가 있는 명령어는 서버 관리자(OP)만 쓸 수 있습니다.

## 빌드

```bash
./gradlew build
```

`build/libs/` 에 만들어진 jar 파일을 서버의 `mods` 폴더에 넣습니다.

## 라이선스

MIT License. 출처(minerapai)만 밝히면 누구나 자유롭게 쓰고 고치고 배포할 수 있습니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 보세요.
