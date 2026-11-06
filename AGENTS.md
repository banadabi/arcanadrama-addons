# ArcanaDrama addons — zorunlu repo kuralları

Bu dosya `banadabi/arcanadrama-addons` reposuna eklenen **bütün mevcut ve gelecek eklentiler** için bağlayıcıdır. Owner’ın 6 Kasım 2026 tarihli kesin kararıdır. Yeni eklenti veya güncelleme hazırlayan her kişi/ajan bu dosyayı önce okur. Bu standart değişmeden farklı dağıtım düzeni eklenmez.

## 1. GSRepo düzeni zorunludur

- `main`: her eklenti için sabit teknik kimliğiyle bir kaynak klasörü, açık Kotlin kaynakları, manifest ve sabit Gradle/derleme girdileri bulunur.
- `builds`: eklentiler kökte `<internalName>.cs3` dosyaları ve tek `plugins.json` listesiyle dağıtılır.
- Tek görünen geliştirici deposu **ArcanaDrama** olur. Sabit kurulum adresi `https://raw.githubusercontent.com/banadabi/arcanadrama-addons/main/repo.json` korunur; yeni sürüm veya kaynak için yeni depo adresi açılmaz.
- Kaynak arşivi `.zip` dosyaları iki dalın repo ağacına **eklenmez**. Karşılık gelen kaynak, derleme ve lisans dosyaları normal dosyalarla sağlanır. `.cs3` biçiminin kendi paket yapısı bu yasağa dahil değildir.
- Ana dalda eski veya yeni **hiçbir CS3 dosyası bulunmaz**. Bütün paketler yalnız `builds` dalının kökünde yer alır. Eski `plugins/` dağıtımı, alias veya uyumluluk istisnası tutulmaz.

## 2. Kimlik ve liste sözleşmesi zorunludur

- Görünen ad `(Arcana Drama) Eklenti İsmi`, `authors` tam olarak `["ArcanaDrama"]` olur. Sabit `internalName` ve plugin giriş sınıfı görünür ad değişikliği nedeniyle değiştirilmez.
- `repo.json` adı `ArcanaDrama`, `manifestVersion` protokol değeri `1` olur; yalnız `https://raw.githubusercontent.com/banadabi/arcanadrama-addons/builds/plugins.json` listesine bağlanır.
- Her listede `url`, `status`, `version`, `name`, `internalName`, `authors`, `description`, `fileSize`, `repositoryUrl`, `language`, `tvTypes`, `apiVersion`, `fileHash` doğru ve dolu olmalıdır. Kimlikler tekildir; listede olmayan CS3 dağıtıma eklenmez.
- Adres **`https://raw.githubusercontent.com/banadabi/arcanadrama-addons/builds/<internalName>.cs3?v=<tam SHA256>`** biçimindedir. `?v=` dosyanın hash’idir; gerçek sürüm ayrıca `version` alanında bulunur.
- `fileHash` tam olarak `sha256-<gerçek dosya SHA256>`, `fileSize` gerçek bayt sayısıdır. Listede, CS3 manifestinde ve kaynak manifestinde sürüm/kimlik aynı olmalıdır. Tahmini hash, boyut veya sürüm yazılmaz.

## 3. Otomatik hazırlama ve değişmez paket

- `tools/release.py` ve `tools/build.py` kullanılır. Kaynak/derleme girdisi değiştiğinde sürüm bir kez artırılır; aynı adayın tekrarı yeniden artırılmaz.
- Aynı kaynak ve mevcut dondurulmuş paket tekrar kullanılır. Sırf metadata/dosya yerleşimi değişti diye kabul edilmiş CS3 yeniden derlenmez veya paketlenmez.
- Ortak derleyici/araç girdileri `release-inputs.json` kimliğine bağlanır. Gerçek kaynak değişikliği eski paket/hash altında gizlenmez.
- Yeni paket, liste ve kaynak durumunu birlikte hazırlayıp doğrula. Eski kabul yeni hash’e otomatik aktarılmaz. `manifestVersion` her eklenti sürümünde artırılmaz.

## 4. Kabul ve yayın kapısı

- Değişen exact CS3/sürüm/hash, orijinal Cloudstream, bağımsız Play ve ArcanaDrama içindeki native Play üzerinde gerekli gerçek katalog/bölüm/video-zaman/uygulanabilir altyazı/sonraki bölüm kabulünü tamamlamadan çalışan public sürüm olarak yayımlanmaz. Bir motorun benzerliği diğerinin testi değildir.
- Geçerli exact paket kabulünü sebepsiz tekrarlama. Kullanıcı kabulünü bağımsız yapılmış cihaz testi gibi yazma; hata veya eksik kabul varsa açık tut.
- Exact bağımsız inceleme, taze ref/CAS, yedek ve sahipli yayın kilidi korunur. Force push, izsiz silme, belirsiz ref yazımını kör tekrar veya otomatik public yayın yapılmaz.
- Otomasyon aday hazırlar; bu kapıları atlamaz. İlgili CI/rule doğrulaması başarısız olan aday kabul edilmez ve yayımlanmaz.
- Yayın öncesi ve sonrasında `python3 tools/validate_repo.py --main <main-checkout> --builds <builds-checkout>` zorunludur. Yeni hazırlanmış sürümün geçici çıktısı için `--preview` kullanılır; bu public kabul değildir.

## 5. Kaynak, hesap ve kapsam

- GPL ve bağımlılık lisansları, karşılık gelen gerçek kaynak/derleme dosyaları, kaynak kimliği ve üyelik yetkileri korunur.
- Parola, token, cookie, imzalı çalışma zamanı medya adresi, kişisel profil veya ham hesap/ağ kayıtları repo’ya konmaz. Özel geliştirme notları private geliştirme reposunda kalır.
- Her kullanıcı gerekiyorsa kendi kaynak hesabıyla kendi cihazında normal giriş yapar. Kaynak oturumu farklı origin/CDN’ye yayılmaz. Kaynak veya oyuncu uyumu için genel ağ/oynatıcı güvenlikleri gevşetilmez.
- Bu repo kuralı backend/Asel/worker/DB/hold değişikliği yetkisi değildir. Sıradaki kaynak mevcut owner sırasına göre ele alınır.

Bu kurala uymayan eklenti veya güncelleme repo’ya kabul edilmez.
