/**
 * Programa do micro:bit (Cutebot) para o controle web deste projeto.
 *
 * Cole em makecode.microbit.org com o editor em JavaScript
 * (extensões necessárias: "bluetooth" e "cutebot").

 * Protocolo:
 *   M,<esq>,<dir>   velocidades de -100 a 100, mantidas até novo comando
 *   S               parar
 *   A | B | C       ações (carinha/LED, buzina, apagar faróis)
 *
 */

bluetooth.startUartService()


const WATCHDOG_MS = 600   // deve ser maior que o heartbeat da página (350 ms)

let lastCommandAt = 0
let driving = false      // só é true depois de um comando de motor de verdade

/*
 * Nunca use control.inBackground() a cada comando: cada chamada cria uma
 * tarefa nova, elas se acumulam e o micro:bit dá panic — parece congelar.
 *
 * A buzina NÃO usa a extensão "music". No micro:bit V2 ela sobe um mixer de
 * áudio (tarefa própria, buffers, interrupções) que disputa tempo com a pilha
 * Bluetooth e derruba a placa em panic.
 *
 * No V2 o alto-falante embutido espelha a saída do P0, então toca sem fio
 * nenhum. No V1 (ou com o alto-falante desativado) sai pelo P0 do conector.
 */
const HORN_PERIOD_US = 1136   // 1 000 000 / 880 Hz
const HORN_DUTY = 300         // 0..1023 — volume; menor puxa menos corrente
const HORN_MS = 250           // duração da buzinada

let wantedFace = IconNames.Asleep
let faceDirty = true
let hornPending = false
let hornOffAt = 0             // 0 = buzina desligada

function stopCar() {
    cuteBot.stopcar()
    driving = false
}

function showFace(face: IconNames) {
    wantedFace = face
    faceDirty = true
}

bluetooth.onBluetoothConnected(function () {
    showFace(IconNames.Yes)
})

bluetooth.onBluetoothDisconnected(function () {
    stopCar()
    showFace(IconNames.Asleep)
})

bluetooth.onUartDataReceived("\n", function () {
    const command = bluetooth.uartReadUntil("\n")
    lastCommandAt = input.runningTime()

    if (command.charAt(0) == "M") {
        // "M,<esq>,<dir>"
        const parts = command.split(",")
        if (parts.length >= 3) {
            cuteBot.motors(parseFloat(parts[1]), parseFloat(parts[2]))
            driving = true
        }
    } else if (command == "S") {
        stopCar()
    } else if (command == "A") {
        showFace(IconNames.Happy)
        cuteBot.colorLight(cuteBot.RGBLights.ALL, 0xffff00)
    } else if (command == "B") {
        showFace(IconNames.Angry)
        hornPending = true
    } else if (command == "C") {
        cuteBot.closeheadlights()
    }
})

// Efeitos: uma única tarefa desenha a carinha e toca a buzina.
basic.forever(function () {
    if (faceDirty) {
        faceDirty = false
        basic.showIcon(wantedFace, 0)   // 0 = não pausa depois de desenhar
    }
    if (hornPending) {
        hornPending = false             // limpa ANTES: buzinada nova não some
        pins.analogSetPeriod(AnalogPin.P0, HORN_PERIOD_US)
        pins.analogWritePin(AnalogPin.P0, HORN_DUTY)
        hornOffAt = input.runningTime() + HORN_MS
    } else if (hornOffAt != 0 && input.runningTime() >= hornOffAt) {
        hornOffAt = 0
        pins.analogWritePin(AnalogPin.P0, 0)   // desliga o pino: silêncio
    }
    basic.pause(20)
})

// Watchdog: sem notícias do controle, o carro para sozinho.
basic.forever(function () {
    if (driving && input.runningTime() - lastCommandAt > WATCHDOG_MS) {
        stopCar()
        showFace(IconNames.No)
    }
    basic.pause(50)
})
