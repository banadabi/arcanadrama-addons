# Derleme ve yayın hazırlığı

`release.py prepare MODULE --state release-state.json` gerçek kaynak/derleme girdileri değiştiğinde bir sonraki sürümü ayırır. Tekrarlanan aynı adayın sürümünü tekrar artırmaz. `register` sadece tam manifest eşleşen CS3’ü değişmez hashli yerel önbelleğe kaydeder. Aynı kaynak/sürüm için farklı paket reddedilir. `catalogue` dosyadan gerçek SHA256/bayt/sürüm okuyup `.cs3?v=<SHA256>` adresli listeyi üretir; `manifestVersion` 1 kalır.

`build.py` ortak araç girdilerini her modülün `release-inputs.json` dosyasına bağlar; ortak araç değişiklikleri de kaynak kimliğine dahildir. `--hydrate BUILDS_CHECKOUT` eski kabul edilmiş dosyaları hash doğrulayarak yerel önbelleğe alır. `--plan` gereken derlemeyi bildirir; `--build` yalnız değişen/kayıtsız girdiyi derler. `--catalogue` dağıtım dosyalarını yeni `dist/` alanına üretir. Eski kabul edilmiş CS3’ler yeniden paketlenmez. Gelecek derlemelerin ZIP metadata’sı deterministiktir.

GitHub Actions ana dal değişikliğinde aday hazırlar; public ref’lerini veya çalışan sistemi kendiliğinden değiştirmez. Adayın exact bağımsız incelemesi ve gerekli uygulama kabulleri sonrası aynı dosyalar `builds` dalına, kaynak/sürüm durumu ana dala CAS ile yayımlanır. Yeni eklentiler kabul edilmeden dağıtım listesine girmez. `dist/` sadece geçici hazırlık alanıdır; repo’ya ZIP/kayıt/log/hesap verisi eklenmez.
