<div align="center">

<br/>

<img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,12,20&height=200&section=header&text=ChalRide&fontSize=80&fontAlignY=38&desc=Real-time%20Ride-Sharing%20for%20Android&descAlignY=60&descSize=22&fontColor=ffffff&animation=fadeIn" width="100%"/>

<br/>

[![Platform](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](/)
[![Language](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](/)
[![Firebase](https://img.shields.io/badge/Firebase-FFCA28?style=for-the-badge&logo=firebase&logoColor=black)](/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-API%2028-blue?style=for-the-badge)](/)
[![Status](https://img.shields.io/badge/Status-Active-success?style=for-the-badge)](/)
[![License](https://img.shields.io/badge/License-MIT-red?style=for-the-badge)](/)

<br/>

> **One APK. Two Roles. Zero Compromise.**
> A production-grade ride-sharing app built from scratch — Rider books, Driver accepts, Trip begins. All in real time.

<br/>

[![⬇ Download APK](https://img.shields.io/badge/⬇%20%20DOWNLOAD%20APK-FF6B35?style=for-the-badge&logo=googledrive&logoColor=white)](https://drive.google.com/file/d/1OlQKwLTSZvhxRkdgu956Y3AmbjrCpT2H/view?usp=sharing)
&nbsp;&nbsp;
[![⭐ Star on GitHub](https://img.shields.io/badge/⭐%20Star%20on%20GitHub-161b22?style=for-the-badge&logo=github&logoColor=white)](https://github.com/anup-Kumar2004/ChalRide---ride-hailing-and-mobility-app)

<br/>

</div>

---

## 📖 Table of Contents

- [What Is ChalRide?](#-what-is-chalride)
- [App Screenshots](#-app-screenshots)
- [Feature Deep-Dive](#-feature-deep-dive)
  - [Auth System](#-auth-system)
  - [Rider Side](#-rider-side)
  - [Driver Side](#-driver-side)
  - [Hidden Engineering](#-hidden-engineering)
- [Navigation Flow](#-navigation-flow)
- [Tech Stack](#-tech-stack)
- [Firebase Setup](#-firebase-setup)
- [API Keys](#-api-keys)
- [Build & Run](#-build--run)
- [Data Model](#-data-model)
- [Known Limitations](#-known-limitations)

---

## 🚗 What Is ChalRide?

ChalRide is a **full-stack, dual-role ride-sharing Android app** — think Uber or Ola, built entirely from scratch in Kotlin and Firebase. Both the Rider and the Driver run inside **the same APK**, on separate phones, communicating with each other in real time.

```
Rider taps "Find a Ride"
  └─▶ Geohash grid query finds nearby drivers
      └─▶ Waterfall engine targets them one-by-one (15s each)
          └─▶ Driver's phone rings with a ride request sheet
              └─▶ Driver accepts → Atomic Firestore transaction (no double-accepts)
                  └─▶ Both maps light up · OTP boarding · Live navigation · Earnings logged
```

Built over **40+ days**, across **20+ screens**, with **2 foreground services**, a **custom geohash engine**, a **watchdog system**, a **multi-stage driver warning system**, and a **crash recovery mechanism** that puts the driver back exactly where they left off after an app kill.

---

## 📸 App Screenshots

> *Real screenshots from the live app. Best experienced on two physical devices — one as Rider, one as Driver.*

---

### 🔐 Auth & Onboarding

| Role Selection | Register | Login | Phone Verify |
|:-:|:-:|:-:|:-:|
| <img src="screenshots/role_selection.jpeg" width="180"/> | <img src="screenshots/register.jpeg" width="180"/> | <img src="screenshots/login.jpeg" width="180"/> | <img src="screenshots/phone_number_verify.jpeg" width="180"/> |

---

### 🧍 Rider Flow

#### 📍 Home & GPS
| Part 1 — Map loads, GPS fetching | Part 2 — Location confirmed, pickup set |
|:-:|:-:|
| <img src="screenshots/rider_home_plus_gps_part_one.jpeg" width="220"/> | <img src="screenshots/rider_home_plus_gps_part_two.jpeg" width="220"/> |

#### 🔍 Destination Search & Route
| Destination Search | Ride Confirm (Part 1) | Ride Confirm (Part 2) |
|:-:|:-:|:-:|
| <img src="screenshots/destination_search.jpeg" width="180"/> | <img src="screenshots/ride_confirm_part_one.jpeg" width="180"/> | <img src="screenshots/ride_confirm_part_two.jpeg" width="180"/> |

> `Ride Confirm has two parts — Part 1 shows the route overview and the collapsed bottom sheet, while Part 2 shows the expanded bottom sheet with vehicle cards, nearby drivers with available vehicle types, and the radius filter panel.   

#### 🔎 Searching & Live Tracking
| Searching for Driver | Live Tracking |
|:-:|:-:|
| <img src="screenshots/searching.jpeg" width="220"/> | <img src="screenshots/live_tracking.jpeg" width="220"/> |

#### 📋 Ride Details (Summary Sheet)
| Part 1 — Driver info & status | Part 2 — Route & ride ID |
|:-:|:-:|
| <img src="screenshots/ride_details_part_one.jpeg" width="220"/> | <img src="screenshots/ride_details_part_two.jpeg" width="220"/> |

#### ✅ OTP, Trip Completion & Cancellation
| Rider OTP Screen | Trip Completed (Part 1) | Trip Completed (Part 2) | Ride Cancelled |
|:-:|:-:|:-:|:-:|
| <img src="screenshots/rider_side_otp_screen.jpeg" width="160"/> | <img src="screenshots/rider_side_trip_completed_part_one.jpeg" width="160"/> | <img src="screenshots/rider_side_trip_completed_part_two.jpeg" width="160"/> | <img src="screenshots/rider_side_ride_cancellation_screen.jpeg" width="160"/> |

> `Trip Completed` scrolls — Part 1 shows fare & trip timeline, Part 2 shows trip details, driver info, rating section & complaint section.

#### 👤 Rider Profile & Trip History
| Rider Profile | Trip History |
|:-:|:-:|
| <img src="screenshots/rider_profile.jpeg" width="220"/> | <img src="screenshots/rider_trip_history.jpeg" width="220"/> |

---

### 🧑‍✈️ Driver Flow

#### 🏠 Driver Home
| Part 1 — Offline state & stats | Part 2 — Online, waiting for requests |
|:-:|:-:|
| <img src="screenshots/driver_home_part_one.jpg" width="220"/> | <img src="screenshots/driver_home_part_two.jpg" width="220"/> |

#### 🔔 Ride Request & Active Ride
| Ride Request Sheet | Active Ride Overview | Turn-by-Turn Navigation |
|:-:|:-:|:-:|
| <img src="screenshots/ride_request.jpg" width="180"/> | <img src="screenshots/active_ride.jpg" width="180"/> | <img src="screenshots/turn_by_turn_navigation.jpg" width="180"/> |

#### 🔑 OTP Verify at Pickup
| Driver OTP Verify Screen |
|:-:|
| <img src="screenshots/driver_side_otp_verify_screen.jpg" width="220"/> |

#### 🏁 Trip Completed
| Part 1 — Earnings & trip summary | Part 2 — Stats & action buttons |
|:-:|:-:|
| <img src="screenshots/driver_side_trip_completed_part_one.jpg" width="220"/> | <img src="screenshots/driver_side_trip_completed_part_two.jpg" width="220"/> |

#### ❌ Ride Cancellation
| Driver Ride Cancelled Screen |
|:-:|
| <img src="screenshots/driver_side_ride_cancellation_screen.jpg" width="220"/> |

#### 💰 Earnings & Profile
| Driver Earnings | Driver Profile |
|:-:|:-:|
| <img src="screenshots/driver_earnings.jpg" width="220"/> | <img src="screenshots/driver_profile.jpg" width="220"/> |

#### ⚠️ Driver Warning System (3-Stage Dialog)
| Stage 1 — Notice (Amber) | Stage 2 — Final Warning (Orange-Red) | Stage 3 — Suspended (Deep Red) |
|:-:|:-:|:-:|
| <img src="screenshots/driver_warning_dialog_part_one.jpg" width="180"/> | <img src="screenshots/driver_warning_dialog_part_two.jpg" width="180"/> | <img src="screenshots/driver_warning_dialog_part_three.jpg" width="180"/> |

> Each stage has a progressively severe color, unique message, and a filled dot meter showing how many warnings have been used.

---

---

## 🔍 Feature Deep-Dive

### 🔐 Auth System

The auth system is **role-aware from the very first frame**. Everything from the splash screen to the start destination is resolved asynchronously before anything is shown.

| Feature | How It Works |
|---|---|
| **Role Selection** | Rider vs Driver baked into registration — login guard checks the correct Firestore collection, not just Firebase Auth |
| **Driver Onboarding** | Two-step: Profile setup (name + optional photo via Cloudinary) → Vehicle setup (type, model, plate, color). Progress tracked via `profileStep` (0, 1, 2) |
| **Phone OTP (Rider)** | 6-digit locally generated OTP delivered as a device notification. 40-second resend cooldown timer. Two-step UI (phone → OTP) with animated transitions |
| **Password Recovery** | Queries both `riders` and `drivers` Firestore collections in **parallel** before sending the reset email — no reset links sent for unregistered emails |
| **Smart Splash** | Splash screen holds open during an async Firestore `profileStep` check. Zero blank-screen flash. Routes each role to their exact correct screen on relaunch |
| **Session Recovery** | App killed mid-driver-setup? On next launch, `profileStep` routes straight to wherever setup was left — profile or vehicle step |

---

### 🧍 Rider Side

#### Home Screen — Where It All Begins

The Rider Home is far more than a map with a search bar. It's a fully reactive, GPS-first screen with graceful fallbacks:

- **Cinematic GPS Zoom** — On first fix, the map animates from an India-wide view down to street level in smooth steps
- **Multi-Strategy Nominatim Search** — Three search strategies run in combination:
  1. Exact query as typed
  2. First-word prefix search (finds "BML Munjal" when you type "bml mun")
  3. Two-word combination search
  - Results are deduplicated by coordinate, sorted by proximity to user
- **Map Tap → Confirm Pickup** — Tapping anywhere on the map places a pickup marker and reverse-geocodes the address. This confirmed state survives rotation, process death, and back navigation
- **Pickup Warning Banner** — If the destination search finds your pickup point is unroutable (e.g. in a building interior), a warning slides up on the rider home screen

#### Destination Search

```
User types → Nominatim autocomplete (3-strategy) → ORS route drawn
         ↓
    3.5-second animated car loading overlay
         ↓
    Route polyline + distance + ETA appears
         ↓
    "Confirm Destination" button activates
```

- **Route Loading Overlay** — A custom `RouteLoadingOverlayController` manages a car animation, pulsing dots, and cycling text messages. Enforces a minimum 700ms display time so it never flashes
- **Change Destination** — After a route is confirmed, tapping the map shows a "Change Destination" hint button (auto-hides after 3s)
- **Routing Error Handling** — Different errors get different messages: unroutable pickup vs unroutable destination vs network timeout (network errors don't break UX)

#### Ride Confirm Screen

| Feature | Detail |
|---|---|
| **Geohash Grid Query** | Builds a 3×3 to 7×7 neighbor grid based on search radius, queries Firestore per cell, deduplicates by driver UID |
| **Freshness Filter** | Drivers with `lastUpdated` older than 5 minutes are excluded from results |
| **Haversine Filter** | After geohash query, Haversine distance validates each driver is truly within the selected radius |
| **Vehicle Cards** | Cards for Bike, Auto, Sedan, SUV — only cards with actual nearby drivers are enabled. Others show "Not available" |
| **Radius Filter** | 5/10/15/25 km chips with confirmation dialog on change (warns about tradeoffs) |
| **Animated Orb** | A glowing purple orb travels the route polyline on the preview map using a smooth easing function |

#### Live Ride Tracking

This is where the real engineering lives.

```
Phase 1: Driver → Pickup
  ├── Driver arrow rotates to match travel bearing
  ├── Status: "on the way" → "getting close" → "almost there"
  ├── Route refreshes every 80m of driver movement
  └── OTP card slides in (Overshoot animation) when driver arrives

Phase 2 (after OTP): Pickup → Destination
  ├── Map resets — shows driver + destination
  ├── New route fetched from pickup to destination
  └── Cancel button hidden (can't cancel mid-trip)
```

**Snap-Back Camera** — After any map touch, the view auto-refits after 9 seconds of idle. Implemented with a `Handler` + `Runnable` that reschedules on every touch.

**Network Banner** — A slide-in banner with three states:
- 🔴 **Offline** — red, persistent
- 🟡 **Unstable** — amber, auto-hides after 6s
- 🟢 **Back Online** — teal, auto-hides after 3s

---

### 🧑‍✈️ Driver Side

#### Going Online

Going online triggers a chain of events:

1. `DriverLocationService` starts as a **foreground service** (type: `location`)
2. Firebase RTDB `onDisconnect()` is registered — if the phone dies or loses network, RTDB automatically writes `isOnline: false`
3. A guard flag (`serviceHasWrittenOnline`) prevents the RTDB listener from clobbering Firestore during service startup
4. Geohash is recalculated on every location update so the driver appears in the correct search cells

#### Ride Request Sheet

- **15-second countdown ring** with visual + audio alert
- Requests that arrive after their window are silently rejected with a "You just missed it" toast — no ghost sheets shown
- The accept button uses `db.runTransaction()` to verify `status == "pending"` AND `targetDriverId == uid` — prevents two drivers from accepting the same ride

#### Turn-by-Turn Navigation

The navigation fragment (`DriverNavigationFragment`) implements:

- **Heading-up orientation** — Map rotates as the driver turns. Bearing computed from position delta or `location.bearing` (whichever is more reliable at current speed)
- **Smooth marker animation** — `ValueAnimator` interpolates driver position between GPS fixes (1.5s duration)
- **Arrival detection** — Within 80m of target, a blinking banner appears and the "Arrived" button activates
- **Recenter button** — Appears after user touches map, hides after recenter

#### OTP & Boarding (DriverArrivedPickupFragment)

This screen has the most complex state management in the app:

```
Arrive at pickup
  ↓
Generate 4-digit OTP → Save to Firestore with timestamp
  ↓
Show to rider (via RideLiveService notification: "OTP: 4382")
  ↓
2:30 countdown timer (turns red at 0:30)
  ↓
Timer expires → "Rider No-Show" dialog
  ├── "Cancel" → performNoShowCancellation() → DriverRideCancelled
  └── "Wait for OTP" → reveals fallback cancel button, driver stays on screen
```

**OTP Resume Logic** — If the driver kills the app and returns:
- Firestore is queried for existing `riderOtp` and `otpSentAt`
- Timer resumes from the **correct remaining seconds** — rider never sees a new OTP code
- This works even after full process death

#### Earnings Screen

Real-time `addSnapshotListener` on two Firestore queries:
- **Total earnings** from the driver document
- **Trip history** sorted by `startedAt`, with completed/cancelled status badges, fare display (strikethrough for cancelled), and date formatting ("Today, 3:42 PM" / "Yesterday" / "5 Mar, 10:15 AM")

---

### 🔧 Hidden Engineering

These are the systems that aren't visible on any single screen but make the app robust:

#### 1 · Driver Offline Watchdog *(runs on Rider's phone)*

When a driver's `isOnline` flips `false` mid-ride, `RideLiveFragment` starts a 5-minute countdown:

```kotlin
for (minutesLeft in 5 downTo 1) {
    delay(60_000L)
    updateStatus("Driver offline. Auto-cancelling in ${minutesLeft-1} min...")
}
// After 5 minutes:
autoCancelDueToDriverOffline()
```

On auto-cancel:
- Ride marked `cancelled` with reason `DRIVER_OFFLINE`
- Driver's `offlineCancelCount` is incremented **from the rider's phone** (works even when driver's device is dead)
- Driver state reset to OFFLINE

#### 2 · Location Staleness Polling

Separate from the watchdog — a 1-minute coroutine reads `lastUpdated` from the driver document. If >5 minutes stale (GPS issues without triggering RTDB disconnect), auto-cancel fires immediately without waiting for the full 5-minute watchdog.

#### 3 · 6-Stage Driver Warning System

Every time a driver causes a cancellation by going offline, their `offlineCancelCount` increases. On next app open:

| Count | Stage | Dialog |
|---|---|---|
| 1–3 | 🟡 **Notice** | Amber dialog, dot meter shows remaining warnings, pulsing glow ring |
| 4–5 | 🟠 **Final Warning** | Orange-red "Account at Risk", ordinal count ("your 4th cancellation") |
| 6+ | 🔴 **Suspended** | Deep red dialog, GO ONLINE button disabled, support email shown |

The dialog is a custom `DialogFragment` with `ValueAnimator` for the pulsing glow ring — each stage has its own emoji, chip label, accent color, and button style. The warning dots fill in based on count.

#### 4 · Waterfall Dispatch Engine

`RideSearchingFragment` doesn't broadcast to all drivers — it targets them **one at a time**:

```
1. Query all available drivers of selected type within radius
2. Sort by distance (closest first)
3. Write targetDriverId → first driver, status → "pending"
4. Start 15-second timer
   ├── Driver accepts → navigate to RideLive
   ├── Driver rejects → targetNextDriver()
   └── 15s timeout → targetNextDriver()
5. Global 60-second cap → NO_DRIVER_FOUND
```

`triedDriverIds` set prevents re-targeting. If the Firestore write fails for a driver, they are **not** marked as tried — safe retry logic.

#### 5 · Atomic Acceptance Transaction

```kotlin
db.runTransaction { transaction ->
    val rideSnap = transaction.get(rideRef)
    val currentStatus = rideSnap.getString("status")
    val currentTarget = rideSnap.getString("targetDriverId")

    if (currentStatus != "pending" || currentTarget != uid) {
        throw Exception("ride_no_longer_available")
    }
    transaction.update(rideRef, mapOf("status" to "accepted", "driverId" to uid))
}
```

If the rider cancelled the exact same millisecond the driver tapped Accept — the transaction aborts. No ghost rides.

#### 6 · Route Cache in Overview

`DriverActiveRideFragment` caches the last fetched route polyline by phase (`HEADING_TO_PICKUP` / `IN_PROGRESS`). On `onResume()`, overlays are cleared and redrawn from cache **instantly** — no network call on screen rotation or back-stack return.

The driver's **starting location** (phase 1) is persisted to Firestore as `tripStartLat`/`tripStartLng`. This ensures the overview route never shifts even as the driver moves toward pickup — it always shows "where you were when you accepted" → pickup.

#### 7 · Mid-Ride Crash Recovery

On every launch, `DriverHomeFragment.checkForActiveRideOnLaunch()` reads `activeRideId`:

```
activeRideId found in Firestore
  ├── status == "arrived_at_pickup" → DriverArrivedPickupFragment (OTP screen)
  ├── status == "in_progress"       → DriverActiveRideFragment (Phase 2)
  └── status == "accepted"          → DriverActiveRideFragment (Phase 1)
```

All arguments are reconstructed from the ride document — the driver is put back exactly where they were.

#### 8 · `RideLiveService` — Sticky Foreground Service

`START_STICKY` foreground service for the rider side. If Android kills it:
- On restart, `intent` is `null`
- Service reads `rideRequestId` from `SharedPreferences`
- Re-attaches Firestore listener
- Updates the persistent notification
- Fragment re-binds and gets live status updates

This means the rider's trip continues tracking even after the app is killed and relaunched.

---

## 🗺 Navigation Flow

```
┌─────────────────────────── AUTH ──────────────────────────────────────────┐
│  RoleSelection → Login → Register                                          │
│               → PasswordRecovery                                           │
│               → RiderPhoneVerify → RiderHome ─────────────────────────┐   │
│               → DriverProfileSetup → DriverVehicleSetup → DriverHome ─┐│  │
└────────────────────────────────────────────────────────────────────────┼┼──┘
                                                                         ││
┌─────────────────────── RIDER FLOW ─────────────────────────────────── ┘│  │
│                                                                          │  │
│  RiderHome → DestinationSearch → RideConfirm → RideSearching            │  │
│       │                                            │                     │  │
│       ├── RiderProfile → RiderTripDetails          ├── RideLive ────────┘  │
│       │                                            │      │                │
│       │                                            │      ├── RideCompletion
│       │                                            │      ├── RideCancelled
│       │                                            │      └── RideSummary
│       │                                            │
│       │                                            └── RideCancelled
│       └── RideLive (rejoin from SharedPrefs)
│
┌─────────────────────── DRIVER FLOW ────────────────────────────────────┘
│
│  DriverHome → DriverActiveRide → DriverNavigation → DriverArrivedPickup
│       │              │                  │                   │
│       │              │                  │                   ├── DriverActiveRide (IN_PROGRESS)
│       │              │                  │                   └── DriverRideCancelled
│       │              │                  ├── DriverRideCompleted → DriverHome
│       │              │                  └── DriverRideCancelled → DriverHome
│       │              └── DriverRideCancelled
│       ├── DriverEarnings
│       └── DriverProfile → DriverEarnings
```

---

## 🛠 Tech Stack

| Layer | Technology |
|---|---|
| **Language** | Kotlin |
| **UI** | XML Layouts · ViewBinding · Material Design 3 |
| **Navigation** | Jetpack Navigation Component |
| **Auth** | Firebase Authentication (Email/Password) |
| **Primary Database** | Cloud Firestore |
| **Presence System** | Firebase Realtime Database |
| **Maps** | OSMDroid (OpenStreetMap) |
| **Routing & ETA** | OpenRouteService Directions API |
| **Geocoding** | Nominatim + Android Geocoder |
| **Location** | FusedLocationProviderClient |
| **Image Upload** | Cloudinary via OkHttp3 (multipart) |
| **Image Loading** | Glide |
| **Async** | Kotlin Coroutines + `lifecycleScope` |
| **Foreground Services** | 2× (location type + dataSync type) |
| **Notifications** | 2 channels per role (silent status + alert events) |
| **Splash Screen** | AndroidX Core SplashScreen API |

---

## 🔥 Firebase Setup

### Step 1 — Create project & download config

```
1. Go to console.firebase.google.com
2. Create New Project → Add Android App
3. Package name: com.example.chalride
4. Download google-services.json → place in /app directory
```

### Step 2 — Enable these services

| Service | Required Setting |
|---|---|
| **Authentication** | Email/Password provider: ON |
| **Cloud Firestore** | Create database in test mode |
| **Realtime Database** | Create in test mode |

### Step 3 — Firestore security rules *(development)*

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} {
      allow read, write: if request.auth != null;
    }
  }
}
```

### Step 4 — Realtime Database rules

```json
{
  "rules": {
    "driverPresence": {
      "$uid": {
        ".read": "auth != null",
        ".write": "auth.uid === $uid"
      }
    }
  }
}
```

### Step 5 — Firestore Composite Indexes Required

> Firebase will show an error with a direct link to create these. Click the link in Logcat.

| Collection | Fields | Order | Used By |
|---|---|---|---|
| `rideRequests` | `driverId`, `status` | ASC, ASC | Driver earnings history |
| `rideRequests` | `riderId` | ASC | Rider trip details |
| `rideRequests` | `targetDriverId`, `status` | ASC, ASC | Driver home listener |
| `drivers` | `isOnline`, `isAvailable`, `vehicleType` | ASC, ASC, ASC | Waterfall dispatch |
| `drivers` | `isOnline`, `isAvailable`, `geohash` | ASC, ASC, ASC | Geohash grid query |

---

## 🔑 API Keys

### OpenRouteService — Routing

```
1. Go to openrouteservice.org → Sign up
2. Create a free token (2,000 requests/day free)
3. In res/values/strings.xml add:
   <string name="ors_api_key">YOUR_KEY_HERE</string>

⚠️  Never commit this file to a public repository
```

### Cloudinary — Driver Photo Upload

```kotlin
// In DriverProfileSetupFragment.kt
val cloudName    = "YOUR_CLOUD_NAME"
val uploadPreset = "YOUR_UNSIGNED_PRESET"
```

Create an **unsigned upload preset** in your Cloudinary dashboard → Settings → Upload → Add upload preset.

### Nominatim — Geocoding

```
No API key required.
Public API with a 1 req/second rate limit.
User-Agent is automatically set to your app's package name.
```

---

## 🚀 Build & Run

```bash
# Prerequisites
# - Android Studio Hedgehog (2023.1.1) or newer
# - JDK 11
# - Physical device strongly recommended (Emulator GPS is limited)

git clone https://github.com/anup-Kumar2004/ChalRide---ride-hailing-and-mobility-app.git
cd ChalRide

# Setup steps:
# 1. Place google-services.json in /app
# 2. Add ORS key to app/src/main/res/values/strings.xml
# 3. Update Cloudinary credentials in DriverProfileSetupFragment.kt

# Build
./gradlew assembleDebug

# Or just open in Android Studio and hit Run ▶
```

> **Best tested on two physical Android phones simultaneously** — one logged in as a Rider, one as a Driver. Install the same APK on both.

---

## 🗄 Data Model

<details>
<summary><b>📄 drivers/{uid}</b></summary>

```json
{
  "name": "string",
  "phone": "+91XXXXXXXXXX",
  "email": "string",
  "photoUrl": "https://res.cloudinary.com/...",
  "vehicleType": "bike | auto | sedan | suv",
  "vehicleModel": "Honda Activa",
  "vehiclePlate": "HR26AB1234",
  "vehicleColor": "Black",

  "isOnline": false,
  "isAvailable": false,
  "driverState": "OFFLINE | ONLINE_AVAILABLE | ON_TRIP_TO_PICKUP | WAITING_AT_PICKUP | IN_TRIP",

  "lat": 28.4595,
  "lng": 77.0266,
  "geohash": "ttnsv",
  "lastUpdated": 1700000000000,

  "activeRideId": null,
  "tripPhase": null,
  "tripStartLat": null,
  "tripStartLng": null,

  "earnings": 1250,
  "totalTrips": 14,
  "profileStep": 2,

  "offlineCancelCount": 0,
  "isAccountFlagged": false
}
```

</details>

<details>
<summary><b>📄 rideRequests/{id}</b></summary>

```json
{
  "riderId": "uid",
  "riderName": "Anup Kumar",

  "pickupLat": 28.4595,
  "pickupLng": 77.0266,
  "pickupAddress": "Sector 17, Gurugram",

  "destLat": 28.5072,
  "destLng": 77.0860,
  "destAddress": "Cyber Hub, DLF",

  "vehicleType": "auto",
  "estimatedFare": 85,

  "status": "pending | accepted | arrived_at_pickup | in_progress | completed | cancelled",
  "targetDriverId": "uid",
  "driverId": "uid",
  "driverName": "Rahul Sharma",

  "riderOtp": "4382",
  "otpSentAt": 1700000000000,

  "createdAt": 1700000000000,
  "assignedAt": 1700000001000,
  "startedAt": 1700000002000,
  "completedAt": 1700000003000,

  "cancellationReason": "RIDER_CANCELLED | DRIVER_OFFLINE | NO_DRIVER_FOUND | RIDER_NO_SHOW",

  "riderFeedback": {
    "rating": 5,
    "complaint": "string",
    "complaintStatus": "pending | resolved",
    "complaintSubmittedAt": 1700000000000
  }
}
```

</details>

<details>
<summary><b>📄 riders/{uid}</b></summary>

```json
{
  "uid": "string",
  "name": "string",
  "email": "string",
  "phone": "+91XXXXXXXXXX",
  "role": "rider",
  "profileStep": 2,
  "phoneVerified": true
}
```

</details>

<details>
<summary><b>📡 driverPresence/{uid} (Realtime DB)</b></summary>

```json
{
  "isOnline": true,
  "lastSeen": 1700000000000
}
```

`onDisconnect()` automatically sets `isOnline: false` + `lastSeen: ServerValue.TIMESTAMP` if the driver's device loses connection or the app is killed. This is the crash detection mechanism.

</details>

---

## ⚠️ Known Limitations

```
▸ OTP is simulated locally — delivered as a device notification, not real SMS
▸ No payment gateway — fares are estimated only, no actual charges
▸ Nominatim public API — 1 req/sec limit, no SLA, not for production scale
▸ Cloudinary unsigned preset — should use signed uploads server-side before production
▸ No surge pricing — fare multipliers not implemented
▸ No real-time chat between rider and driver
```

---

## 📁 Project Structure

```
app/src/main/java/com/example/chalride/
├── data/
│   ├── model/         User.kt
│   └── repository/    AuthRepository.kt
├── ui/
│   ├── auth/          LoginFragment, RegisterFragment, RoleSelectionFragment,
│   │                  RiderPhoneVerifyFragment, PasswordRecoveryFragment,
│   │                  AuthViewModel
│   ├── driver/        DriverHomeFragment, DriverActiveRideFragment,
│   │                  DriverNavigationFragment, DriverArrivedPickupFragment,
│   │                  DriverRideCompletedFragment, DriverRideCancelledFragment,
│   │                  DriverEarningsFragment, DriverProfileFragment,
│   │                  DriverProfileSetupFragment, DriverVehicleSetupFragment,
│   │                  DriverLocationService, DriverNotificationManager,
│   │                  DriverState, DriverWarningDialog, RideRequestSheet,
│   │                  TripEarningsAdapter, TripPhase, CancelReason, ...
│   └── rider/         RiderHomeFragment, DestinationSearchFragment,
│                      RideConfirmFragment, RideSearchingFragment,
│                      RideLiveFragment, RideCompletionFragment,
│                      RideCancelledFragment, RiderProfileFragment,
│                      RiderTripDetailsFragment, RideSummaryFragment,
│                      RideLiveService, NotificationHelper,
│                      RouteLoadingOverlayController, DashedRoadView
└── utils/             BackPressHandler.kt
```

---

<div align="center">

<br/>

## Built with 🔥 by

**Anup Kumar**
B.Tech Computer Science · BML Munjal University

<br/>

[![Portfolio](https://img.shields.io/badge/Portfolio-FF6B35?style=for-the-badge&logo=firefox&logoColor=white)](https://anup-kumar2004.github.io)
[![GitHub](https://img.shields.io/badge/GitHub-161b22?style=for-the-badge&logo=github&logoColor=white)](https://github.com/anup-Kumar2004)
[![LinkedIn](https://img.shields.io/badge/LinkedIn-0A66C2?style=for-the-badge&logo=linkedin&logoColor=white)](https://linkedin.com/in/your-profile)

<br/>

[![⬇ Download APK](https://img.shields.io/badge/⬇%20%20DOWNLOAD%20APK-FF6B35?style=for-the-badge&logo=googledrive&logoColor=white)](https://drive.google.com/your-link-here)

<br/>

*If this project taught you something, a ⭐ goes a long way.*

<br/>

<img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&customColorList=6,12,20&height=120&section=footer" width="100%"/>

</div>
