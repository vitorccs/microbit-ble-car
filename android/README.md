# Micro:bit Car — app Android

Equivalente nativo da página `../index.html`. Mesmo protocolo, mesma mistura de
motores, mesmo heartbeat — só que sobre a pilha Bluetooth do Android, porque
Web Bluetooth não existe dentro de uma WebView (um wrapper Cordova/Capacitor
não funcionaria sem um plugin BLE nativo de qualquer forma).

## Como compilar

Pelo Android Studio: abra a pasta `android/` e rode. Pela linha de comando:

```bash
cd android
export JAVA_HOME=/home/vitorccs/Documents/android-studio/jbr   # o java do sistema é só JRE
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O `local.properties` (com `sdk.dir`) fica fora do git; o Android Studio o recria.

Toolchain: Gradle 9.7.1, AGP 9.4.0 (Kotlin embutido, sem o plugin
`org.jetbrains.kotlin.android`), compileSdk 37, minSdk 26.

## Estrutura

| Arquivo | Papel |
|---|---|
| `Protocol.kt` | UUIDs do UART, constantes e a mistura tanque (`motionFor`) |
| `BleLink.kt` | busca, conexão GATT e o escritor serializado |
| `CarController.kt` | estado da UI e a bomba de envio (heartbeat, fila de ações) |
| `Ui.kt` | D-pad, botões A/B/C/D, slider, log, seletor de dispositivo |
| `MainActivity.kt` | permissões, tela cheia, teclado/gamepad |

## Diferenças em relação à página web

- **Seleção do dispositivo**: o navegador mostra o seletor do Chrome; aqui o app
  faz o scan e mostra a própria lista (o micro:bit não anuncia o serviço UART,
  então o filtro é pelo nome, como na web).
- **Permissões**: `BLUETOOTH_SCAN` e `BLUETOOTH_CONNECT` no Android 12+,
  `ACCESS_FINE_LOCATION` abaixo disso. São pedidas no primeiro toque no botão
  Bluetooth.
- **Teclado**: além de setas/WASD/JKL, os botões de gamepad A/B/X/Y estão
  mapeados para A/B/C/D.
- A tela é travada em paisagem e as barras do sistema ficam escondidas, para que
  um deslize acidental não roube o toque de um botão segurado.

## Protocolo (idêntico ao da web)

```
M,<esq>,<dir>   velocidades de -100 a 100, mantidas até o próximo comando
S               parar
A | B | C       ações de um disparo
```

Cada comando vai com `\n`, porque o micro:bit lê com `uartReadUntil("\n")`.
Enquanto o carro anda, o app reenvia o comando a cada 350 ms para alimentar o
watchdog do micro:bit.
