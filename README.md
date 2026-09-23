# AndroidAirPlay

Android-приложение на Kotlin и Jetpack Compose для трансляции аудио с любого Android-устройства и ТВ-приставок (Android 10+) на **Apple HomePod mini** и другие AirPlay-колонки.

---

## 🎯 Почему это работает?

В дистрибутиве **Bazzite (Linux)** звук транслируется на HomePod mini благодаря звуковому серверу **PipeWire** (`module-raop-sink` и `module-raop-discover`), который реализует протокол **Apple RAOP (AirPlay 1)**.

HomePod mini поддерживает этот протокол, если в приложении **Apple «Дом»** открыт доступ для устройств в локальной сети. Данное Android-приложение реализует точно такое же сетевое ядро на Android:

1. **mDNS Zeroconf Discovery**: автоматическое обнаружение `_raop._tcp` и `_airplay._tcp` в локальной Wi-Fi сети.
2. **RTSP Handshake**:
   - `OPTIONS`
   - `ANNOUNCE` с криптографическим рукопожатием (генерация 16-байтного ключа AES-128, шифрование открытым мастер-ключом Apple RSA-2048 с OAEP-паддингом, согласование SDP `AppleLossless`)
   - `SETUP` (открытие и согласование UDP-портов: аудио, control, timing)
   - `RECORD` (старт сессии воспроизведения)
   - `SET_PARAMETER` (управление громкостью HomePod в реальном времени)
   - `TEARDOWN` (корректное завершение)
3. **RTP Audio Streamer**: нарезка аудио на чанки по 352 фрейма (8 мс), упаковка в ALAC uncompressed контейнер, шифрование AES-128-CBC и отправка по UDP.
4. **NTP Timing Synchronizer**: непрерывная синхронизация таймстемпов по UDP Timing Port для исключения джиттера и рассинхронизации буфера.
5. **System Audio Capture (`AudioPlaybackCapture` API)**: перехват звука других приложений (Spotify, Яндекс Музыка, YouTube Music, браузер) на Android 10+ без Root-прав.
6. **Built-in Test Tone Generator**: генератор тестовой мелодии (аккорд A-Major 44.1kHz stereo) для моментальной проверки подключения колонки в 1 клик.

---

## ⚙️ Настройка HomePod mini (Обязательно!)

Чтобы HomePod mini принимал звук от Android-устройств (так же, как и от Linux/Bazzite), настройте доступ в приложении **«Дом» (Apple Home)** на iPhone, iPad или Mac:

1. Откройте приложение **«Дом»**.
2. Нажмите кнопку меню **«…»** (в правом верхнем углу) -> **«Настройки дома»** (*Home Settings*).
3. Перейдите в раздел **«Динамики и ТВ»** (*Speakers & TV*).
4. В пункте **«Разрешить доступ»** выберите: **«Всем в этой сети»** (*Anyone on the Same Network*).
5. Убедитесь, что Android-смартфон и HomePod mini подключены к одной и той же Wi-Fi сети.

---

## 📱 Скриншот и возможности интерфейса

- **Автопоиск**: быстрое сканирование колонок в Wi-Fi сети.
- **Ручное добавление IP**: если роутер блокирует multicast-пакеты (AP Isolation), можно вручную ввести IP-адрес HomePod mini.
- **Плавный регулятор громкости**: аппаратное управление громкостью динамика HomePod в диапазоне от 0% до 100% (с логарифмической компенсацией восприятия звука).
- **Выбор источника звука**:
  - *System Audio (Any App)* — захват любого играющего звука на телефоне.
  - *Test Tone* — встроенный мелодичный тест без необходимости включать плеер.
- **Фоновый сервис (Foreground Service)**: воспроизведение не прерывается при блокировке экрана смартфона благодаря `WIFI_MODE_FULL_HIGH_PERF` и `PARTIAL_WAKE_LOCK`. В шторке уведомлений доступно управление и кнопка «Stop».

---

## 📦 Установка APK

Готовый скомпилированный APK находится по пути:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Установка через ADB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Либо скопируйте файл `app-debug.apk` на смартфон любым удобным способом (Telegram, локальная сеть, USB) и откройте его на устройстве.

---

## 🛠️ Сборка из исходников

Для сборки проекта на компьютере требуется Java 17/21 и Android SDK (API 35):

```bash
# Сборка Debug APK:
./gradlew assembleDebug

# Запуск тестов и линтинга:
./gradlew check
```

---

## 🏛️ Структура проекта

```
app/src/main/java/com/homepod/airplay/
├── audio/
│   ├── AudioCaptureManager.kt   # Захват системного аудио через MediaProjection и AudioPlaybackCapture
│   └── TestToneGenerator.kt     # Синтезатор тестового 44.1kHz стерео-сигнала
├── crypto/
│   └── AirPlayCrypto.kt         # Apple RSA-2048 OAEP шифрование AES ключей и AES-128-CBC
├── data/model/
│   ├── AirPlayDevice.kt         # Модель найденного устройства
│   └── StreamState.kt           # Состояния стрима и типы источников
├── discovery/
│   └── AirPlayDiscovery.kt      # mDNS Zeroconf сканер (_raop._tcp)
├── protocol/
│   ├── BitWriter.kt             # Битовый упаковщик для ALAC PCM
│   ├── RTSPClient.kt            # Клиент протокола RTSP (OPTIONS, ANNOUNCE, SETUP, RECORD, VOLUME, TEARDOWN)
│   └── RtpAudioSender.kt        # UDP RTP стример аудиоданных и обработчик NTP-таймингов
├── service/
│   └── AirPlayAudioService.kt   # Foreground Service с уведомлением и удержанием Wi-Fi соединения
└── ui/
    ├── screens/HomeScreen.kt    # Compose UI интерфейс
    ├── theme/                   # Material 3 стили и цвета
    └── MainActivity.kt          # Главное Activity, запрос системных разрешений
```
