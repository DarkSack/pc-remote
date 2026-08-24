# PC Remote — Mobile (Android)

App Android para controlar el agente de escritorio. React Native + Expo + TypeScript.

Ver la [documentación general](../README.md) y [`docs/`](../docs/) para arquitectura, protocolo y roadmap.

## Estado

✅ **Fase 3d — MVP completo**:

- Core de red: protocol / crypto (Ed25519) / discovery (mDNS) / pairing /
  connection (FSM + reconexión + streams + ping/pong) / secure store.
- UI:
  - **Discovery** (`app/index.tsx`): lista dispositivos emparejados + escaneo
    mDNS de la LAN con los agentes no emparejados.
  - **Pairing** (`app/pair/[host].tsx`): input de código 6 dígitos + nombre
    del dispositivo, con estados connecting/waiting_code/confirming/done/error.
  - **Dashboard** (`app/dashboard/[deviceId].tsx`): tiles de CPU/RAM en tiempo
    real (`systeminfo.stats` @1s), info del sistema, botones de power
    (Bloquear / Suspender / Log off / Reiniciar / Apagar) con confirmación,
    unpair.

## Correr en desarrollo

```bash
npm install
npx expo start
```

Abre en Expo Go (Android) o en un emulador. Requiere estar en la misma red LAN que el agente.

## Estructura

```
mobile/
├── app/                        # expo-router
│   ├── _layout.tsx
│   ├── index.tsx               # discovery + emparejados
│   ├── pair/[host].tsx         # pairing con código 6 dígitos
│   └── dashboard/[deviceId].tsx  # tiles + info + power
└── src/
    ├── net/
    │   ├── protocol.ts         # tipos WS
    │   ├── crypto.ts           # Ed25519
    │   ├── discovery.ts        # mDNS
    │   ├── pairing.ts          # PairingSession
    │   └── connection.ts       # ConnectionManager
    ├── storage/
    │   └── secure.ts           # credentials
    ├── stores/
    │   └── connection.ts       # zustand
    ├── ui/
    │   └── theme.ts            # tokens
    └── types/
        └── react-native-zeroconf.d.ts
```
