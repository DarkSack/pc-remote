# PC Remote — Mobile (Android)

App Android para controlar el agente de escritorio. React Native + Expo + TypeScript.

Ver la [documentación general](../README.md) y [`docs/`](../docs/) para arquitectura, protocolo y roadmap.

## Estado

📐 **Fase 3c** — core de red implementado end-to-end (mismo protocolo que el agente):

- ✅ `src/net/protocol.ts` — tipos completos del protocolo
- ✅ `src/net/crypto.ts` — Ed25519 keygen + sign (via `@noble/ed25519`)
- ✅ `src/net/discovery.ts` — mDNS con `react-native-zeroconf`
- ✅ `src/net/pairing.ts` — flujo pair_init → código → pair_confirm → guardar creds
- ✅ `src/net/connection.ts` — FSM completo (connect → auth challenge → session), reconexión con backoff exponencial, ping/pong 15/5s, request/response y subscribe/stream con timeouts
- ✅ `src/storage/secure.ts` — credenciales en `expo-secure-store` (Android Keystore)
- ✅ `src/stores/connection.ts` — zustand store para estado global

Fase 3d (próxima): UI real de discovery, pairing y dashboard.

## Correr en desarrollo

```bash
npm install
npx expo start
```

Abre en Expo Go (Android) o en un emulador. Requiere estar en la misma red LAN que el agente.

## Estructura

```
mobile/
├── app/                     # expo-router
│   ├── _layout.tsx
│   ├── index.tsx            # placeholder (Fase 3d lo reemplaza con discovery)
│   ├── pair/[deviceId].tsx  # placeholder
│   └── dashboard/index.tsx  # placeholder
└── src/
    ├── net/
    │   ├── protocol.ts      # tipos WS
    │   ├── crypto.ts        # Ed25519
    │   ├── discovery.ts     # mDNS
    │   ├── pairing.ts       # flujo de pairing
    │   └── connection.ts    # FSM + reconexión + streams
    ├── storage/
    │   └── secure.ts        # credentials
    └── stores/
        └── connection.ts    # zustand
```
