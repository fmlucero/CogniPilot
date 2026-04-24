# CogniPilotRemote — Backend en Vercel

Guía de qué hay que armar en el repo `CogniPilotRemote` (https://github.com/fmlucero/CogniPilotRemote)
para que el panel web pueda actualizar el horario permitido de la app Android
**en tiempo real** vía Firebase Cloud Messaging.

> Esto es solo la explicación conceptual. El código se hace después; este
> archivo es para tener clara la arquitectura antes de empezar.

---

## 1. Qué tiene que hacer el backend

Tres cosas, en este orden de prioridad:

1. **Guardar el horario permitido** (rango horario + flag enabled + timezone) de
   forma persistente.
2. **Exponer un endpoint público de lectura** (`GET`) por si la app necesita
   sincronizarse manualmente o como fallback.
3. **Disparar un push a Firebase** cada vez que el supervisor cambia el horario,
   para que la app Android se entere en menos de un segundo y muestre la
   notificación "Horario actualizado por supervisor".

Y un cuarto componente para que sea usable por una persona:

4. **Panel HTML mínimo con login** para que el supervisor edite el horario sin
   tocar terminal ni Postman.

---

## 2. Stack recomendado

- **Next.js 14 (App Router)** desplegado en Vercel — ya viene listo para
  serverless functions y soporta páginas estáticas + API routes en el mismo repo.
- **Vercel KV** (Redis manejado, free tier alcanza de sobra) para guardar el
  horario actual. Alternativa más simple: un único registro en Vercel Postgres
  o incluso un JSON en Vercel Blob. KV es lo más liviano para un solo registro.
- **Firebase Admin SDK** para enviar pushes a un topic FCM desde el backend.
- **Auth básica con un único usuario admin** vía cookie firmada o `iron-session`.
  Para esta app no necesitamos multi-tenant ni roles.

No hace falta ORM, ni base de datos relacional, ni framework de auth pesado.

---

## 3. Modelo de datos

Un único registro en KV con esta forma:

| Campo       | Tipo   | Ejemplo                            | Descripción                                              |
| ----------- | ------ | ---------------------------------- | -------------------------------------------------------- |
| `enabled`   | bool   | `true`                             | Si está en `false` la app no aplica restricción horaria. |
| `from`      | string | `"08:00"`                          | Inicio del rango permitido, formato `HH:mm`, 24h.        |
| `to`        | string | `"18:00"`                          | Fin del rango permitido. Puede cruzar medianoche.        |
| `tz`        | string | `"America/Argentina/Buenos_Aires"` | Timezone IANA (la app la usa para evaluar localmente).   |
| `updatedAt` | number | `1714000000000`                    | Epoch ms del último cambio (auditoría).                  |
| `updatedBy` | string | `"facu"`                           | Usuario que hizo el cambio (auditoría).                  |

---

## 4. Endpoints

### `GET /api/schedule`

- Público, sin auth.
- Devuelve el JSON del horario actual exactamente como está en KV.
- La app Android lo usa como fallback si no recibe pushes (red mala, sin Google
  Play Services, primer arranque). No es el camino principal.

### `POST /api/schedule`

- Protegido con auth (cookie de sesión válida o header `Authorization` con un
  token compartido — bastará con cookie del panel).
- Recibe `{enabled, from, to, tz}`, valida formato, escribe en KV.
- **Después de escribir**, dispara el push FCM (ver siguiente sección).
- Responde con el snapshot guardado más `updatedAt`.

### `POST /api/login` y `POST /api/logout`

- Login con un único usuario (env vars `ADMIN_USER` y `ADMIN_PASSWORD_HASH` con
  bcrypt). Setea cookie `httpOnly` + `secure`.
- Logout la limpia.

---

## 5. Cómo enviar el push desde Vercel a la app

El servicio FCM ya está integrado en la app Android (commit `6528a2d`):

- `ScheduleMessagingService` recibe el mensaje, persiste el snapshot y muestra
  la notificación.
- La app se suscribe al topic `schedule-updates` al iniciar.

Lo que tiene que hacer el backend cuando cambia el horario:

1. Inicializar el Firebase Admin SDK con la **service account** (descargada de
   Firebase Console → Project Settings → Service accounts → Generate new private
   key). Esto nunca va al repo: se guarda como variable de entorno en Vercel
   (`FIREBASE_SERVICE_ACCOUNT_JSON` con el JSON completo en una sola línea).
2. Llamar a `messaging.send` apuntando a `topic: "schedule-updates"` con un
   **data message** (no notification) que contenga estos campos como strings:
   - `type` = `"schedule_update"`
   - `enabled` = `"true"` o `"false"`
   - `from` = `"08:00"`
   - `to` = `"18:00"`
   - `tz` = `"America/Argentina/Buenos_Aires"`

   Importante: tienen que ser strings, FCM data messages no aceptan otros tipos.

3. Si la llamada falla, devolver 500 al panel para que el supervisor sepa que
   el cambio quedó persistido pero el push no llegó. La app igual se va a
   sincronizar en el próximo arranque vía `GET /api/schedule` (fallback).

---

## 6. Panel HTML

Una sola página `/admin`:

- Form con: switch `enabled`, input `from` (time picker), input `to` (time
  picker), select `tz` (default Buenos Aires), botón "Guardar".
- Muestra el `updatedAt` y `updatedBy` actuales arriba del form.
- Botón "Probar push" que dispara un `POST /api/schedule` con los valores
  actuales sin cambiar nada — útil para verificar que el dispositivo recibe.
- No hace falta más. Cero JS framework de UI: HTML + un fetch.

Login en `/login` con form simple.

---

## 7. Variables de entorno en Vercel

Agregar en Project Settings → Environment Variables (Production + Preview):

| Variable                                | Valor                                                          |
| --------------------------------------- | -------------------------------------------------------------- |
| `FIREBASE_SERVICE_ACCOUNT_JSON`         | JSON completo de la service account, en una sola línea.        |
| `ADMIN_USER`                            | Usuario del panel, ej `"facu"`.                                |
| `ADMIN_PASSWORD_HASH`                   | bcrypt hash del password (no el password en texto plano).      |
| `SESSION_SECRET`                        | Random 32+ bytes para firmar la cookie de sesión.              |
| `KV_REST_API_URL` y `KV_REST_API_TOKEN` | Vercel KV los autogenera al crear el store y los inyecta solo. |

---

## 8. Flujo completo end-to-end

```
Supervisor edita horario en panel
    │
    ▼
POST /api/schedule (auth ok, body válido)
    │
    ├── Escribe en Vercel KV
    │
    ├── Llama Firebase Admin → messaging.send(topic="schedule-updates")
    │
    ▼
FCM entrega data message a todos los dispositivos suscriptos (~ms)
    │
    ▼
ScheduleMessagingService.onMessageReceived en la app Android
    │
    ├── ScheduleRepository.save(snapshot)   → SharedPreferences
    │
    └── Notificación "📢 Horario actualizado por supervisor"
            │
            ▼
LogisticsAccessibilityService consulta el snapshot en cada evento
    │
    ├── Si dentro del rango → silencia overlays (app funciona normal)
    └── Si fuera del rango → muestra carteles como hasta ahora
```

---

## 9. Setup paso a paso (cuando arranquemos)

1. Crear proyecto Next.js en local, pushear a `CogniPilotRemote`.
2. Conectar el repo a Vercel (Vercel detecta Next.js automáticamente).
3. Crear Vercel KV store desde el dashboard, asociarlo al proyecto.
4. Bajar la service account de Firebase Console y pegarla en
   `FIREBASE_SERVICE_ACCOUNT_JSON`.
5. Generar el bcrypt del password admin localmente y pegarlo como
   `ADMIN_PASSWORD_HASH`.
6. Implementar las 4 API routes (`GET /api/schedule`, `POST /api/schedule`,
   `POST /api/login`, `POST /api/logout`).
7. Implementar las 2 páginas (`/login`, `/admin`).
8. Probar end-to-end desde el panel: editar → ver notificación en el celular →
   ver que el cartel rojo deja de salir cuando estamos en horario permitido.

---

## 10. Consideraciones operativas

- **Cold starts en Vercel**: el primer request después de inactividad puede
  tardar 1–2 segundos. Para un panel de admin no importa.
- **FCM no garantiza entrega instantánea**: en el 99% de casos llega en menos
  de un segundo, pero puede haber demora si el dispositivo está dormido o sin
  red. La app refresca al próximo arranque vía el GET, así que el peor caso
  es "el supervisor cambia el horario y el repartidor lo ve cuando vuelve a
  abrir la app".
- **Costos**: Vercel Hobby + KV free tier + FCM gratis cubren cómodamente esta
  carga (un cambio de horario por día y unos pocos dispositivos).
- **Backup del horario**: Vercel KV no hace backup automático en el plan free.
  Si esto se vuelve crítico, hacer un cron diario que copie el JSON a Vercel
  Blob o GitHub Gist.
- **Auditoría**: con `updatedAt` + `updatedBy` ya hay rastro mínimo. Si en algún
  momento se requiere historial completo, agregar una segunda key en KV
  (`schedule:history`) que sea una lista append-only.
