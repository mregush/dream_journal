# Развёртывание MVP на VPS

Два контейнера: бот на Java 21 и PostgreSQL 17. Данные хранятся в постоянном Docker volume
и сохраняются при перезапуске и обновлении приложения. HTTP-порты и порт PostgreSQL наружу не публикуются;
для long polling домен не требуется.

## 1. Подготовить VPS

Для первой небольшой аудитории: 1–2 vCPU, 2 ГБ RAM, 30 ГБ SSD и Ubuntu LTS.
Добавить свой SSH-ключ и установить Docker Engine с Compose v2 по
[официальной инструкции](https://docs.docker.com/engine/install/ubuntu/).
Нужен исходящий HTTPS-доступ к Telegram. Java и Gradle на сервере не нужны.

Склонировать репозиторий на сервер, например в `/opt/dream-journal`.
Команды далее выполнять из каталога проекта пользователем с доступом к Docker.

## 2. Собрать и передать образ

На локальной машине с Java 21 и запущенным Docker:

```shell
./gradlew test bootJar
docker build -t dream-journal:local .
./scripts/smoke-test.sh
```

Проверка использует отдельную временную базу и фиктивный токен. Она проверяет запуск, миграции,
обновление и сохранение записей при перезапуске контейнеров, затем удаляет только свои тестовые ресурсы.
GitHub Actions выполняет те же проверки при push/PR, без публикации образа и обновления VPS.

Для обычного VPS на x86_64 собрать образ явно под `linux/amd64`, включая сборку на Mac с Apple Silicon:

```shell
docker buildx build --platform linux/amd64 --load -t dream-journal:release-001 .
docker save dream-journal:release-001 | gzip > /tmp/dream-journal-release-001.tar.gz
scp /tmp/dream-journal-release-001.tar.gz user@SERVER_IP:/tmp/
```

Для ARM64 VPS использовать `linux/arm64`. Архитектуру сервера показывает `uname -m`.
На сервере загрузить образ:

```shell
gzip -dc /tmp/dream-journal-release-001.tar.gz | docker load
```

## 3. Заполнить настройки

На сервере:

```shell
cp .env.prod.example .env.prod
chmod 600 .env.prod
```

В редакторе задать:

- `APP_IMAGE=dream-journal:release-001` — тег загруженного образа.
- `TELEGRAM_BOT_TOKEN` — рабочий токен BotFather.
- `DB_PASSWORD` — отдельный пароль, например сгенерированный через `openssl rand -hex 32`.

Остальные параметры уже имеют значения по умолчанию. Файл `.env.prod` исключён из Git и Docker-образа.
Docker Compose передаёт настройки контейнерам автоматически. Для проверки конфигурации без вывода секретов:

```shell
docker compose --env-file .env.prod -f compose.prod.yaml config --quiet
```

Имя проекта `COMPOSE_PROJECT_NAME` после запуска сохранять прежним: оно определяет имя тома базы.
Изменение `DB_PASSWORD` в файле не меняет пароль уже созданного пользователя PostgreSQL.

## 4. Запустить бота

Остановить локальный экземпляр с тем же токеном, затем на сервере выполнить:

```shell
./scripts/deploy.sh
```

Скрипт запускает PostgreSQL, останавливает старый процесс бота и запускает новый, ожидая готовности.
Миграции применяются автоматически. После запуска проверить `/start`, `/diary`, `/calendar` и `/stats`.
Для дальнейшей локальной разработки использовать отдельного тестового бота.

Проверка состояния и логов:

```shell
docker compose --env-file .env.prod -f compose.prod.yaml ps
docker compose --env-file .env.prod -f compose.prod.yaml logs --tail=100 app
```

`healthy` означает завершённый запуск приложения, миграций и регистрации long polling.
При отключённом Telegram проверяется только запуск приложения. Последующая доступность Telegram
этим маркером не проверяется. Завершившиеся процессы перезапускаются автоматически;
логи ограничены тремя файлами до 10 МБ на сервис.

## 5. Обновить приложение

1. Проверить новый коммит и собрать образ с новым тегом, например `release-002`.
2. Передать и загрузить его на VPS, как при первом запуске.
3. Обновить checkout репозитория и `APP_IMAGE` в `.env.prod`.
4. Выполнить `./scripts/deploy.sh` и проверить команды бота.

Если позднее будет использоваться реестр образов, указать полный адрес в `APP_IMAGE` и запускать
`./scripts/deploy.sh --pull`: образ загрузится до остановки текущего процесса.
Обновления выполнять последовательно; один токен должен использоваться одним работающим экземпляром.
