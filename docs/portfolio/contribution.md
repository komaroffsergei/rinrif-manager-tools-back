# Личный вклад — Manager Tools — backend

Подтверждённые идентичности: `skomarov@reinform.ru`, `init.reg@gmail.com`. Merge-коммиты и созданные при архивировании снимки исключены. Другие авторы сохранены в Git без изменения авторства.

## Примеры изменений

### Add ATR2Spec backend module

[e5e1b35b6026](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/e5e1b35b60261bb21fb1c72ca8fa01a0504ea75c) · 2026-05-11

Затронуты: `README.md`, `atr2spec/pom.xml`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecConfig.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecException.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecExporter.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecExtractor.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecIo.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecMarkdownGenerator.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecModels.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecPairing.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecService.java`, `atr2spec/src/main/java/ru/reinform/rinrif/managertools/atr2spec/Atr2SpecTextUtils.java`.

```text
19 files changed, 2301 insertions(+)
```

### Поддержать скрытые файлы в поиске GitCut

[858a0f9345ee](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/858a0f9345eec0e865ce99f4af4f0543e2ee5422) · 2026-04-24

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/SearchService.java`, `core/src/main/java/ru/reinform/rinrif/managertools/core/SearchSupport.java`, `model/src/main/java/ru/reinform/rinrif/managertools/model/ApiModels.java`.

```text
3 files changed, 89 insertions(+), 4 deletions(-)
```

### MGSNSMART-17676 Raise backend version for PAT env fallback

[94e0227e19af](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/94e0227e19afd1df8318128b1d9f41c54602fa10) · 2026-04-23

Затронуты: `.gitignore`, `README.md`, `core/src/main/java/ru/reinform/rinrif/managertools/core/AppConfig.java`, `remote-api/pom.xml`, `server/pom.xml`, `server/src/main/java/ru/reinform/rinrif/managertools/config/ManagerToolsConfig.java`.

```text
6 files changed, 133 insertions(+), 25 deletions(-)
```

### Fix GitLab PAT config fallback

[c9616c0f758e](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/c9616c0f758e9620dc9f66e96b60f66ab9c49c42) · 2026-04-23

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/AppConfig.java`.

```text
1 file changed, 17 insertions(+), 4 deletions(-)
```

### MGSNSMART-17676 Переданы credentials из GitLab URL

[1c5487977c74](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/1c5487977c74dc2ee7987244a680ddc4c1ce3b26) · 2026-04-22

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/GitSupport.java`.

```text
1 file changed, 35 insertions(+), 2 deletions(-)
```

### MGSNSMART-17676 Поддержан приватный URL для GitLab

[2386e6cb65f6](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/2386e6cb65f66ba1867d5de86ef9d423fbec74c7) · 2026-04-22

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/GitSupport.java`, `core/src/main/java/ru/reinform/rinrif/managertools/core/ManagerToolsService.java`, `core/src/main/java/ru/reinform/rinrif/managertools/core/RepositoryManager.java`.

```text
3 files changed, 17 insertions(+), 5 deletions(-)
```

### MGSNSMART-17676 Ограничен fetch целевой веткой

[d79bbe57d1d8](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/d79bbe57d1d82eb0542d9bd42590c3122cd08db0) · 2026-04-22

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/GitSupport.java`, `core/src/main/java/ru/reinform/rinrif/managertools/core/RepositoryManager.java`, `core/src/main/java/ru/reinform/rinrif/managertools/core/SearchService.java`.

```text
3 files changed, 99 insertions(+), 15 deletions(-)
```

### MGSNSMART-17676 Исправлен вызов открытия Git-репозитория

[2b7176e44f7d](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/2b7176e44f7defa1dc48fc4ac8c9ce611596dc34) · 2026-04-22

Затронуты: `core/src/main/java/ru/reinform/rinrif/managertools/core/GitSupport.java`.

```text
1 file changed, 2 insertions(+), 2 deletions(-)
```

## Полный перечень коммитов

| Дата | Изменение | Коммит |
|---|---|---|
| 2026-08-12 | Add release trace report | [cdb7cd2517](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/cdb7cd2517bc508cbeefd6e03b62902090771008) |
| 2026-05-11 | Add ATR2Spec backend module | [e5e1b35b60](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/e5e1b35b60261bb21fb1c72ca8fa01a0504ea75c) |
| 2026-04-24 | Bump manager-tools backend snapshot version | [ffbb6648ad](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/ffbb6648adad74745e629b354586057cf4f73cb5) |
| 2026-04-24 | Поддержать скрытые файлы в поиске GitCut | [858a0f9345](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/858a0f9345eec0e865ce99f4af4f0543e2ee5422) |
| 2026-04-24 | MGSNSMART-17676 Bump manager-tools backend snapshot version | [f10fc151f8](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/f10fc151f80344912d6db8683324a6ac6b8df216) |
| 2026-04-23 | MGSNSMART-17676 Raise backend version for PAT env fallback | [94e0227e19](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/94e0227e19afd1df8318128b1d9f41c54602fa10) |
| 2026-04-23 | Fix GitLab PAT config fallback | [c9616c0f75](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/c9616c0f758e9620dc9f66e96b60f66ab9c49c42) |
| 2026-04-22 | MGSNSMART-17676 Переданы credentials из GitLab URL | [1c5487977c](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/1c5487977c74dc2ee7987244a680ddc4c1ce3b26) |
| 2026-04-22 | MGSNSMART-17676 Поддержан приватный URL для GitLab | [2386e6cb65](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/2386e6cb65f66ba1867d5de86ef9d423fbec74c7) |
| 2026-04-22 | MGSNSMART-17676 Ограничен fetch целевой веткой | [d79bbe57d1](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/d79bbe57d1d82eb0542d9bd42590c3122cd08db0) |
| 2026-04-22 | MGSNSMART-17676 Исправлен вызов открытия Git-репозитория | [2b7176e44f](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/2b7176e44f7defa1dc48fc4ac8c9ce611596dc34) |
| 2026-04-22 | MGSNSMART-17676 Использован JGit для работы с репозиториями | [9d07828923](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/9d0782892339eb9e29e61b61263eda4653ce4094) |
| 2026-04-22 | MGSNSMART-17676 Разрешено клонирование без GitLab PAT | [fbab2eaa36](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/fbab2eaa36b1faa0169bd71256e065ec7b8570c8) |
| 2026-04-22 | MGSNSMART-17676 Асинхронное добавление репозитория | [90d094ccd3](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/90d094ccd3754d6b0ed485cdc6d894033aafbd42) |
| 2026-04-16 | Align Maven coordinates with deploy job | [15d50aa7af](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/15d50aa7afee107a970503cdeb0766399490a46d) |
| 2026-04-16 | Add manager tools Java backend | [584a4e4b1b](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/584a4e4b1bc146770602a28a35ee30e047da995a) |
| 2026-04-16 | Initial commit | [b0afb7e5e7](https://github.com/komaroffsergei/rinrif-manager-tools-back/commit/b0afb7e5e7ec9b1aed2be8fbe801785e471f60e6) |
