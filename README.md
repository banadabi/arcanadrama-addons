# ArcanaDrama

Shorts Drama Cloudstream eklentileri. Geliştirici: **ArcanaDrama**.

## Kurulum

Cloudstream’e bu depo adresini bir kez ekle:

```text
https://raw.githubusercontent.com/banadabi/arcanadrama-addons/main/repo.json
```

Bağımsız Play ve ArcanaDrama içindeki **Play → Ayarlar → Eklentiler → Eklenti ekle → CloudStream** yolunda da aynı adres kullanılır. Arcana için [güncel APK](https://arcanadrama.download/apk) kullanılabilir; kaynak hesabı ayarları 76 ve sonrası sürümlerde bulunur.

| Eklenti | Sürüm | Kapsam |
| --- | --- | --- |
| (Arcana Drama) Shortİzle | 4 | Kısa dramalar, bölümler ve kaynak altyazıları |
| (Arcana Drama) Narto | 5 | Kısa dramalar, MovieBox anime ve film/dizi katalogları |
| (Arcana Drama) İzleGeç | 4 | Kısa dramalar, kaynak altyazıları; Cloudstream ve Arcana76+ içinde kendi hesap girişi |
| (Arcana Drama) FullDrama | 5 | Dizi kataloğu, gerçek bölümler, doğrudan CDN oynatma ve isteğe bağlı kaynak hesabı |
| (Arcana Drama) DramaTR | 1 | Sayfalı katalog, gerçek bölüm sırası, doğrudan oynatma ve kaynak altyazıları |

Kaynak hesabı girişi orijinal Cloudstream ve Arcana76+ eklenti ayarlarından yapılır; güncel Arcana sürümü 1.1.46(78)dir. Bağımsız Play1.2.7de bu düğme henüz yoktur; [ayar desteği PRı](https://github.com/liberta09/Play/pull/3) açıktır. Her kullanıcı kendi kaynak hesabıyla cihazında isteğe bağlı giriş yapar.

## Zorunlu repo kuralı

Bu repoya eklenen **bütün eklentiler** [AGENTS.md kurallarına](AGENTS.md) uymak zorundadır. Dal/kaynak düzeni, ZIP yasağı ve gerçek sürüm/SHA/bayt sözleşmesi CI’da kontrol edilir. Kurala uymayan aday kabul edilmez.

## Depo düzeni

- **main:** [ArcanaShortizle](ArcanaShortizle), [ArcanaNarto](ArcanaNarto), [ArcanaIzleGec](ArcanaIzleGec), [ArcanaFullDrama](ArcanaFullDrama), [ArcanaDramaTR](ArcanaDramaTR) kaynakları, Gradle projeleri ve derleme araçları.
- **builds:** kurulacak `.cs3` dosyaları ve tek [plugins.json](https://github.com/banadabi/arcanadrama-addons/blob/builds/plugins.json) listesi.
- Kurulum adresi sabittir. Paket adresindeki `?v=SHA256` içeriği önbellekten ayırır; gerçek sürüm numarası `version` alanındadır.

## Derleme ve sürümleme

JDK17 ve Android SDK37 kurulu olmalı. `python3 tools/build.py --hydrate /path/to/builds-checkout --build --catalogue` kullanılır. Yerel derlemede önce `builds` dalını `.published/` içine ayrı checkout olarak alın; ardından `bash gradlew make` de aynı akışı çalıştırır. Her kaynak klasörü kendi sabit SDK/AGP/Kotlin bağımlılıklarını taşır.

Kaynak değişirse sürüm otomatik bir artar; aynı kaynak ve dondurulmuş paket yeniden kullanılır. `version`, manifest, gerçek SHA256 ve bayt sayısı birlikte doğrulanır. GitHub Actions derleme çıktısını aday olarak hazırlar; bağımsız inceleme ve gerekli uygulama kabulleri sonrası dağıtım güncellenir. Ayrıntı: [tools/README.md](tools/README.md).

## Kaynak ve lisans

[GPL-3.0](LICENSE). Her sürümün Kotlin kaynakları, derleme dosyaları, derleme için gereken shim ve üçüncü taraf lisansları normal dosyalar halinde ilgili klasördedir. Kaynak arşivi ZIP dosyaları repo ağacında tutulmaz. [SOURCE-PROVENANCE.json](SOURCE-PROVENANCE.json) paketlerin karşılık gelen kaynak dosyalarını ve sürüm geçmişini kaydeder.

## Destek ol

[☕ Buy Me a Coffee — banadabi](https://www.buymeacoffee.com/banadabi)
