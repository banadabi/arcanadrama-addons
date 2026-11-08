# (Arcana Drama) DramaDizilerim

Yayıncının güncel sayfalı kataloğu, normal arama formu ve canlı katalog API'si kullanılır. Tam başlık/açıklama/kapak, kaynak puanı, gerçek kategori/platform ve artan gerçek sezon/bölüm bağlantıları korunur. Eksik bölüm sayısından bağlantı üretilmez.

Her bölüm açılışında güncel sayfanın JSON Flight kayıtları JavaScript çalıştırmadan okunur; seçilen yapım/sezon/bölüm ve yayıncının tekil bölüm kimliği bağlanır. Normal /api/playback yanıtındaki HLS/MP4 ve varsa yayımlanmış uyumluluk adresleri kullanılır. Biletler üretilmez/değiştirilmez veya Episode.data içine kalıcı yazılmaz. Normal yayıncı refresh=1 yolu yalnız çözümlenen medya başarısız olduğunda bir kez denenir; kaynak erişim/kota engeli korunur.

Yalnız API'nin gerçekten verdiği zamanlı altyazılar sunulur. Çeviri/generasyon endpoint'i çağrılmaz ve istemcinin otomatik dil varyantları eklenmez. Kaynak hesabını destekleyen hostta normal /giris sayfasında isteğe bağlı kendi hesabın kullanılabilir; parola gömülmez, kaynak oturumu başka origin'e yayılmaz. Bağımsız Play1.2.7 eklenti ayarı düğmesini sunmadığından bu hostta hesap penceresi kabul edilmiş değildir.

Tam arama, yayıncının normal HTML sonuç sayfasını; hızlı arama yayımlanmış altı sonuçlu API'yi kullanır. Dizin sayfalaması yayıncının gerçek sonraki sayfa bağlantısıyla sınırlıdır. Yalnız gözlenen doğrudan HLS/MP4/uyumluluk yanıtı desteklenir; farklı embed türü geçerli video gibi sunulmaz. Ses dili, bütün katalog veya gözlenmeyen arka plan yenilemesi kanıtı iddia edilmez.

Özgün Kotlin MainAPI/Plugin ve ArcanaDrama'nın kendi HTTP/normal hesap penceresi iskeleti. GPL-3.0; sabit kimlik ArcanaDramaDizilerim, giriş dev.arcana.dramadizilerim.ArcanaDramaDizilerimPlugin. Android37 SDK/JDK17 ile bash ./gradlew :provider:testDebugUnitTest :provider:assembleRelease; ana tools/build.py immutable sürümü hazırlar. Final yayın aynı paket iki orijinal uygulamada gerçek akışla kabul edilince yapılır.

Flight denetim satırları JSON modelinden ayrılır; aynı kimlikle gelen kontrol satırları bölüm verisi sanılmaz. Gerçek çelişkili JSON modelleri ve yanlış seçili bölüm reddedilir.

Sonraki bölümlerdeki yapım düzeyindeki SEO canonical adresi ile seçili bölümün og:url adresi ayrı doğrulanır; farklı yapım, yabancı origin ve çelişen sezon/bölüm reddedilir.
