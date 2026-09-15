# AGT Studio ZIP → APK Builder

Gerçek Android APK üretim motoru. ZIP içindeki `index.html` ve web dosyalarını iappyxOS tabanlı WebView shell içine yerleştirir, manifesti günceller ve Android Keystore ile imzalar.

Bu dal kişisel kullanım içindir.

## Build

GitHub Actions > **Build AGT Studio APK** > **Run workflow**.

Workflow, GitHub-hosted Android runner üzerinde gerekli SDK/JDK araçlarıyla projeyi derler ve APK'yı artifact olarak yükler. GitHub Actions workflow'ları repository içindeki `.github/workflows` altında çalışır.
