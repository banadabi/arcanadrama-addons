# (Arcana Drama) DramaVakti

Özgün standart Cloudstream MainAPI sağlayıcısı. Yayımlanmış ana sayfa/katalog ve normal oynatıcının gerçek bölüm sayfasındaki medya adresi kullanılır. Sayfalama ve Türkçe dublaj/altyazı liste filtreleri korunur. Canlı arama API'si hızlı arama, normal katalog formu tam arama için kullanılır.

Tam kaynak başlığı, açıklama, kapak, yıl, kaynak puanı, tür/yayımcı bilgisi ve gerçek artan bölüm bağlantıları taşınır. Yalnız mevcut bağlantılar kullanılır, bölüm sayısından liste uydurulmaz. Medya türü yayımlanan hls/file/auto beyanı ve sınırlı gerçek medya başlığıyla belirlenir. Mevcut zamanlı kaynak altyazıları sunulur; otomatik altyazı çevirisi yapılmaz.

Kaynak hesabı destekleyen hostun eklenti ayarından normal hesap sayfasında isteğe bağlı açılır. Parola/kullanıcı hesabı gömülmez; kaynak çerezleri başka origin/CDN'ye yayılmaz. Misafirde gizli WebView çalıştırılmaz. Kaynak HTTP429 yanıtının sayı veya HTTP-date Retry-After sınırı uygulamanın kendi özel alanında saklanıp provider yeniden yüklenince de korunur. Remote medya normal yayımlanan istemci gibi çerez ve Referer olmadan alınır. Süreli adresler her bölüm açılışında taze sayfadan okunur; gözlenmeyen arka plan yenilemesi veya ses dili kanıtı iddia edilmez.

Teknik kimlik ArcanaDramaVakti; sınıf dev.arcana.dramavakti.ArcanaDramaVaktiPlugin. GPL-3.0; ArcanaDrama'nın kendi derleme/normal kaynak hesap penceresi/HTTP iskeletinden yararlanır. Kaynak/bağımlılık lisansları korunur. Android37 SDK ve JDK17+ ile bash ./gradlew :provider:testDebugUnitTest :provider:assembleRelease; ana tools/build.py sürüm ve immutable paketi hazırlar. Final yayın öncesi aynı paket iki orijinal uygulamada gerçek akışla kabul edilir.
