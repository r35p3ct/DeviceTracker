# DeviceTracker (Android)

Клиентский APK для отслеживания устройств. Приложение собирает позицию (GPS), данные сот (cell) и Wi-Fi, и публикует их по MQTT на ваш собственный сервер. Оператор-независимое позиционирование: координаты резолвятся на сервере по собранным cell/Wi-Fi данным.

**Минимум инфраструктуры:** MQTT-брокер (например, Mosquitto) + небольшой бэкенд с одним endpoint `POST /provision`. Всё остальное делает приложение.

## Как это работает

1. Пользователь вводит в приложении адрес вашего бэкенда (и опционально provisioning-токен).
2. При первом запуске приложение генерирует `device_id` (UUID) и сохраняет локально.
3. Приложение шлёт `POST {url}/provision` и получает конфиг: MQTT-брокер, креды, интервал, топики.
4. Далее работает автономно: собирает телеметрию и публикует в MQTT.
5. При отсутствии связи точки пишутся в локальный offline-буфер (файл) и уходят пачкой после восстановления соединения — пропусков нет.

## Сборка

Требуется Android Studio (или JDK 17+ и Android SDK, Platform 34+).

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Подпись release-версии: сгенерируйте keystore и настройте `signingConfigs` в `app/build.gradle.kts`.

## Установка

1. Разрешите установку из неизвестных источников на устройстве.
2. Соберите APK (`app-debug.apk`) и скопируйте на устройство.
3. Запустите, введите URL бэкенда и provisioning-токен (если требуется), нажмите «Save and start tracking».
4. Выдайте разрешения: геолокация «всегда», уведомления (Android 13+).
5. Рекомендуется в системных настройках снять ограничение фоновой работы для приложения (батарея).

## Протокол

### Bootstrap: `POST /provision`

Запрос:
```json
{
  "device_id": "uuid",
  "model": "Pixel 7",
  "version": "1.0",
  "token": "optional provisioning token"
}
```

Ответ `200`:
```json
{
  "broker": "ssl://mqtt.example.com:8883",
  "username": "device-user",
  "password": "device-pass",
  "interval_sec": 15,
  "topic_telemetry": "devices/{id}/telemetry",
  "topic_status": "devices/{id}/status",
  "topic_config": "devices/{id}/config",
  "topic_cmd": "devices/{id}/cmd"
}
```

Обязательное поле — только `broker`. Остальные опциональны; если `topic_*` не переданы, используются значения по умолчанию `devices/{device_id}/telemetry`, `.../status`, `.../config`, `.../cmd`.

### MQTT

| Топик | Направление | QoS | Retain | Назначение |
|---|---|---|---|---|
| `devices/{id}/telemetry` | APK → сервер | 1 | нет | JSON-телеметрия |
| `devices/{id}/status` | APK → сервер | 1 | да | `online`/`offline` (offline — через LWT) |
| `devices/{id}/config` | сервер → APK | 0 | опц. | смена настроек, например `{"interval_sec": 30}` |
| `devices/{id}/cmd` | сервер → APK | 0 | нет | команда `force_update` |

Телеметрия:
```json
{
  "device_id": "uuid",
  "ts": 1690000000,
  "loc": {"lat": 55.75, "lng": 37.61, "acc": 12, "provider": "gps", "speed": 1.2},
  "cell": [{"radio": "LTE", "mcc": 250, "mnc": 1, "tac": 123, "cid": 4567, "rsrp": -82, "serving": true}],
  "wifi": [{"bssid": "aa:bb:cc:dd:ee:ff", "rssi": -70}],
  "battery": 87,
  "mock": false
}
```

`loc`, `cell` и `wifi` присутствуют только если данные получены.

## Минимальный бэкенд (пример)

Подойдёт любой MQTT-брокер. Логика сервера сводится к:

1. Обработать `POST /provision`: зарегистрировать `device_id`, выдать MQTT-креды.
2. Подписаться на `devices/+/telemetry` и складывать сообщения в базу.
3. Подписаться на `devices/+/status` для online/offline.
4. (Опционально) позиционирование по cell/Wi-Fi: импортировать OpenCellID и наполнять собственную БД при доверенных GPS-фиксах.

## Позиционирование по сотам/Wi-Fi

При доверенном GPS-фиксе приложение шлёт текущую и соседние соты и Wi-Fi. Сервер пишет их координаты в собственную БД (`cell_db`, `wifi_db`) — со временем накапливается карта. Приоритет резолва: своя свежая БД → OpenCellID → агрегатор. Без GPS позиция определяется по сотам (сотни метров) и Wi-Fi (десятки метров).

## Быстрый старт (тест на Raspberry Pi)

Пример минимального стенда лежит в `server/`: Mosquitto + сервис `/provision` на чистом Python (без зависимостей).

```bash
cd server
docker compose up -d --build
# mosquitto  на :1883
# provision на :8080
```

Дальше:

1. Узнайте IP Pi в локальной сети (`hostname -I`), например `192.168.1.50`.
2. На телефоне (та же сеть) запустите приложение, введите `http://192.168.1.50:8080` и нажмите «Save and start tracking».
3. Проверьте приём на Pi:
   ```bash
   docker exec -it tracker-mosquitto mosquitto_sub -v -t 'devices/+/telemetry'
   docker exec -it tracker-mosquitto mosquitto_sub -v -t 'devices/+/status'
   ```

Точки пойдут на `devices/<device_id>/telemetry`. Устройство идентифицируется по `device_id` (UUID), который показан на первом экране приложения.

> Тестовая конфигурация Mosquitto — без TLS и паролей, только для локальной сети.
> Для продакшена добавьте TLS и аутентификацию (см. секцию «Протокол»).

## Лицензия

MIT — см. [LICENSE](LICENSE).
