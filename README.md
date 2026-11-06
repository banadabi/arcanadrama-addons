# ArcanaDrama

Shorts Drama Cloudstream eklentileri. Geliştirici: **ArcanaDrama**.

## Kurulum

Cloudstream’e bu depo adresini bir kez ekle:

```text
https://raw.githubusercontent.com/banadabi/arcanadrama-addons/main/repo.json
```

Bağımsız Play ve ArcanaDrama içindeki **Play → Ayarlar → Eklentiler → Eklenti ekle → CloudStream** yolunda da aynı adres kullanılır. Arcana için [1.1.43(75) veya sonrası](https://arcanadrama.download/apk) gerekir.

| Eklenti | Sürüm | Kapsam |
| --- | --- | --- |
| (Arcana Drama) Shortİzle | 4 | Kısa dramalar, bölümler ve kaynak altyazıları |
| (Arcana Drama) Narto | 3 | Kısa dramalar, MovieBox anime ve film/dizi katalogları |
| (Arcana Drama) İzleGeç | 4 | Kısa dramalar, kaynak altyazıları; Cloudstream’de kendi hesap girişi |

İzleGeç hesap girişi orijinal Cloudstream’in eklenti ayarlarından yapılır. Mevcut Play1.2.7/Arcana75’te hesap ayar düğmesi yoktur; bu sürümlerde misafir bölümleri desteklenir.

## Zorunlu repo kuralı

Bu repoya eklenen **bütün eklentiler** [AGENTS.md kurallarına](AGENTS.md) uymak zorundadır. Dal/kaynak düzeni, ZIP yasağı ve gerçek sürüm/SHA/bayt sözleşmesi CI’da kontrol edilir. Kurala uymayan aday kabul edilmez.

## Depo düzeni

- **main:** [ArcanaShortizle](ArcanaShortizle), [ArcanaNarto](ArcanaNarto), [ArcanaIzleGec](ArcanaIzleGec) kaynakları, Gradle projeleri ve derleme araçları.
- **builds:** kurulacak `.cs3` dosyaları ve tek [plugins.json](https://github.com/banadabi/arcanadrama-addons/blob/builds/plugins.json) listesi.
- Kurulum adresi sabittir. Paket adresindeki `?v=SHA256` içeriği önbellekten ayırır; gerçek sürüm numarası `version` alanındadır.

## Derleme ve sürümleme

JDK17 ve Android SDK37 kurulu olmalı. `python3 tools/build.py --hydrate /path/to/builds-checkout --build --catalogue` kullanılır. Yerel derlemede önce `builds` dalını `.published/` içine ayrı checkout olarak alın; ardından `bash gradlew make` de aynı akışı çalıştırır. Her kaynak klasörü kendi sabit SDK/AGP/Kotlin bağımlılıklarını taşır.

Kaynak değişirse sürüm otomatik bir artar; aynı kaynak ve dondurulmuş paket yeniden kullanılır. `version`, manifest, gerçek SHA256 ve bayt sayısı birlikte doğrulanır. GitHub Actions derleme çıktısını aday olarak hazırlar; bağımsız inceleme ve gerekli uygulama kabulleri sonrası dağıtım güncellenir. Ayrıntı: [tools/README.md](tools/README.md).

## Kaynak ve lisans

[GPL-3.0](LICENSE). Her sürümün Kotlin kaynakları, derleme dosyaları, derleme için gereken shim ve üçüncü taraf lisansları normal dosyalar halinde ilgili klasördedir. Kaynak arşivi ZIP dosyaları repo ağacında tutulmaz. [SOURCE-PROVENANCE.json](SOURCE-PROVENANCE.json) mevcut paketlerin değişmeden taşınan kaynak dosyalarını kaydeder.

## Destek ol

[☕ Buy Me a Coffee — banadabi](https://www.buymeacoffee.com/banadabi)
