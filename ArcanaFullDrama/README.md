# (Arcana Drama) FullDrama

Standart Cloudstream MainAPI sağlayıcısı; teknik kimlik `ArcanaFullDrama`.

FullDrama dizi kataloğu, gerçek bölüm bağlantıları ve doğrudan CDN oynatma sağlayıcısı. İsteyen kullanıcı destekleyen uygulamanın eklenti ayarından kendi FullDrama hesabıyla giriş yapabilir. Teknik sürüm kaynak manifestinde ve birleşik GSRepo listesinde bulunur.

## Kaynak sözleşmesi

- Katalog `/api/series?page=N` çıplak JSON dizisidir. Son sayfa sabitlenmez; gerçek boş yanıt sayfalamayı sonlandırır. Kategori etiketleri normal kaynak sayfasından cihazda alınır; yeni platformlar katalogdan atılmaz.
- Hızlı arama `/api/search?q=`, tam arama `/search?q=` sunulan HTML sonuçlarını kullanır. İlk tam sonuç sayfası canlı gözlemde 30 karttır; bütün sonuçların tek arama çağrısında döndüğü iddia edilmez.
- Detay `.dp-info`, `.dp-desc`, `.dp-poster` ve gerçek `.dp-ecard` bağlantılarından okunur. Gerçek sezon/bölüm sırası ve boşluklar korunur. Kaynak toplamı ile bulunan bağlantı sayısı çelişirse sahte bölüm listesi üretilmez.
- `Episode.data` yalnız sürümlü, sabit kaynak bölüm rotasını içerir. Medya adresi, oturum veya süreli token içermez. `load` bu JSON’u hızlı reddeder, `loadLinks` aynı şemayı çözer.
- Her oynatmada `/api/episode` yeni `playerUrl` verir. `ajax_token.php` embed tokeninden ayrı stream bileti üretir; `/secure-view/` biletinin kayıtlı SW sözleşmesindeki çözümü gerçek CDN adresini verir. Embed tokeni stream tokeni olarak kullanılmaz.
- Uzantısız medya adresinin ilk 512 baytı yalnız Cookie/Referer olmadan Range isteğiyle okunur. Gerçek MP4/ftyp, HLS veya DASH bildirimi callback türünü belirler; yayıncının yanlış hls etiketi MP4e zorlanmaz. Yanıt tamamlanana kadar iptal işlemi bağlantıyı keser. CDN’ye kaynak hesabının çerezleri gönderilmez. Kaynak başlığı/arayüz dili gerçek ses veya altyazı kanıtı sayılmaz.
- Yalnız gözlenen NetShort MP4 sunucusunda dosya 1 MiBden büyükse o konumdan 32 KiBlık tek Range kontrolü yapılır. Gerçek okuma hatasında kaynağın verdiği bölüm kimliği ve imzayla normal `/ajax/refresh.php` akışı bir kez denenir; imza üretilmez veya kaydedilmez. Host ön yüklemesini ayıramadığı için normal/background kuyruğu kullanılır. Bekleyen işte hazırlanıyor mesajı verilir; aynı bölüm için cihazda 60 saniye içinde ikinci yenileme işi gönderilmez. Yeni bağlantı da okunmuyorsa döngü durur. Diğer sunucuların oynatma yolu değişmez.
- Gerçek ayrı altyazı bildirimi varsa callback tamamlanmadan gönderilir; açık `tr`/`tr-TR` bildirimi `tr` olarak normalleştirilir. Otomatik çeviri/üretim yoktur.

## Oturum ve erişim

Kaynağın normal WebView oturumu kullanılır. Parmak izi uydurulmaz, webdriver/GPU gizlenmez, sabit hesap veya token bulunmaz. Gerektiğinde destekleyen hostun eklenti ayarından **FullDrama hesabı** açılır; kullanıcı kendi cihazında kaynak sitesine giriş yapar. Hesap çerezleri bu uygulamanın kaynak WebView deposunda kalır; buluta veya başka CDN’ye taşınmaz.

Sunucunun `wait` yanıtı kendi süresiyle en fazla bir kez tekrar edilir. CAPTCHA/block/üyelik, `processing` ve bağlantı hatası ayrı sonuçlardır; sınırsız yeniden deneme veya gizli tarama yapılmaz. Genel host ağ/oynatıcı politikası değiştirilmez. Her `loadLinks` taze çözüm alır; aynı eski URL’ye tekrar denemek yenileme sayılmaz. Uzun duraklatma sonrası hostun tekrar `loadLinks` çağırmasını gerektiren durumlar ayrıca doğrulanacaktır.

## Derleme ve yayın

SDK `d9c55e2e5b`, mevcut AGP/Kotlin/Gradle/Jsoup girdileri sabittir. `bash gradlew :provider:testDebugUnitTest :provider:assembleRelease` ilgili parser kontrollerini ve release AAR’ı üretir. Test JSON uygulaması yalnız JVM test bağımlılığıdır; CS3’e paketlenmez.

Public dağıtım yalnız aynı exact CS3’ün Cloudstream, bağımsız Play ve ArcanaDrama native Play kabulünden sonra ana GSRepo düzenindedir. Kaynaklar `main/ArcanaFullDrama`, paket `builds/ArcanaFullDrama.cs3?v=<tamSHA256>`, tek birleşik `plugins.json`; ZIP arşivi veya eski alias yoktur. Sürüm/hash/boyut `tools/release.py` ve `tools/build.py` ile gerçek çıktıdan hazırlanır. Kaynak ve dağıtım kimliği bu dosyalarla birlikte korunur.
