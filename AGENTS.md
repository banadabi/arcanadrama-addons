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

## 4. Kabul ve yayın kapısı — owner 8 Kasım son talimatı

Güncel kabul çifti **orijinal Cloudstream + ArcanaDrama içindeki Play** olur. Arcana mutlaka canlı `/app/latest` ile doğrulanan son sürüm olmalıdır. Bağımsız Play ve daha eski üç-host/iki-original-host/geçici yalnızCS hükümleri bu son talimatla aşılmıştır.

- Aynı exact CS3 sürümü/hash iki güncel hedefte gerçek katalog/detay/bölüm/video-zaman, varsa kaynak altyazısı ve sonraki bölüm kapsamında kabul edilir. Owner kabulü kendi kimliğiyle ve sınırlarıyla kaydedilir; yapılmamış bağımsız test uydurulmaz.
- Geçen değişmeyen exact paket gereksiz yeniden test edilmez veya derlenmez. Bağımsız Play’in bilinen erken-next/bölüm açma hatası eklentiye veya güncel Arcana’ya genellenmez.
- Bağımsız exact CODE/OPS, taze ref/CAS, yedek/30GiB ve sahipli3600s yayın kilidi korunur. Force push, belirsiz ref yazımında kör tekrar veya otomatik public yayın yoktur.
- Otomasyon yalnız aday hazırlar; kabul ve CI/rule kapılarını atlamaz.
- Yayın öncesi ve sonrası `python3 tools/validate_repo.py --main MAIN --builds BUILDS` zorunludur. Yeni çıktı için `--preview` sadece hazırlık doğrulamasıdır.

## 5. Kaynak, hesap ve kapsam

- GPL ve bağımlılık lisansları, karşılık gelen gerçek kaynak/derleme dosyaları, kaynak kimliği ve üyelik yetkileri korunur.
- Parola, token, cookie, imzalı çalışma zamanı medya adresi, kişisel profil veya ham hesap/ağ kayıtları repo’ya konmaz. Özel geliştirme notları private geliştirme reposunda kalır.
- Her kullanıcı gerekiyorsa kendi kaynak hesabıyla kendi cihazında normal giriş yapar. Kaynak oturumu farklı origin/CDN’ye yayılmaz. Kaynak veya oyuncu uyumu için genel ağ/oynatıcı güvenlikleri gevşetilmez.
- Bu repo kuralı backend/Asel/worker/DB/hold değişikliği yetkisi değildir. Sıradaki kaynak mevcut owner sırasına göre ele alınır.

Bu kurala uymayan eklenti veya güncelleme repo’ya kabul edilmez.
