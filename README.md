# AllChat — Messenger, Instagram DM, WhatsApp dalam satu aplikasi

Geser kiri/kanan (atau ketuk tab di atas):
**Messenger → Instagram → WhatsApp**

Setiap halaman memuat versi web resmi masing-masing layanan, jadi login cukup sekali dan tersimpan.

---

## Cara mendapatkan APK (tanpa Android Studio, lewat GitHub)

1. Buat akun di github.com (gratis) kalau belum punya.
2. Klik **New repository** → beri nama `AllChat` → **Create repository**.
3. Klik **uploading an existing file**, lalu seret **semua isi** folder AllChat (termasuk folder `.github`) → **Commit changes**.
   - Kalau folder `.github` tidak ikut terupload (folder berawalan titik kadang tersembunyi), buat manual: **Add file → Create new file**, ketik nama `.github/workflows/build-apk.yml`, lalu tempel isi file tersebut.
4. Buka tab **Actions** → tunggu proses "Build APK" selesai (±3–5 menit, tanda centang hijau).
5. Klik hasil build itu → di bagian **Artifacts** unduh `AllChat-apk` → ekstrak zip → dapat `app-debug.apk`.
6. Kirim APK ke HP, buka, izinkan **Install dari sumber tidak dikenal**.

## Cara alternatif: Android Studio
Buka folder `AllChat` di Android Studio → tunggu sync → **Build → Build APK(s)**.

---

## Saat pertama dipakai

- **Messenger**: login dengan akun Facebook.
- **Instagram**: login dengan akun Instagram.
- **WhatsApp**: muncul QR code. Karena tidak bisa memindai layar HP sendiri, pilih
  **"Log in with phone number" / "Tautkan dengan nomor telepon"** di halaman WhatsApp Web,
  masukkan nomormu, lalu ketik kode 8 digit yang muncul di aplikasi WhatsApp:
  *WhatsApp → Perangkat tertaut → Tautkan perangkat → Tautkan dengan nomor telepon*.
  (Aplikasi WhatsApp asli tetap harus terpasang, AllChat jadi "perangkat tertaut".)

Tampilan Messenger & WhatsApp memakai mode desktop (agar tidak dialihkan ke Play Store), jadi
cubit untuk zoom kalau tulisannya kecil.

## Batasan yang perlu diketahui

- **Tidak ada notifikasi push** saat aplikasi ditutup — ini batasan versi web. Tetap pasang
  aplikasi aslinya kalau butuh notifikasi, AllChat dipakai untuk membalas.
- Panggilan suara/video kemungkinan tidak berfungsi.
- Mengunduh foto/file dari chat bisa tidak jalan; mengirim foto/file dari galeri bisa.
- Kalau suatu saat Meta/WhatsApp mengubah situs webnya, alamat di `MainActivity.kt`
  (daftar `services`) mungkin perlu disesuaikan.
