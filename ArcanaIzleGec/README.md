# (Arcana Drama) İzleGeç

ArcanaDrama’nın standart Cloudstream CS3 eklentisi. İç kimlik ArcanaIzleGec, sürüm4.

İzleGeç kataloğu, gerçek dizi/bölüm kayıtları, kaynak HLS oynatımı ve sunulan altyazılar desteklenir. Giriş gerektiren bölümler kaynağın üyelik yetkisine tabidir.

## Hesap girişi

Orijinal Cloudstream’de eklentinin dişli/ayar düğmesi → İzleGeç hesabı → kaynak sitesinde kendi kullanıcı adı ve şifrenizle giriş → Kapat. Dizi sayfasını yeniden açın. Hesabın erişebildiği bölümler yüklenir. Her kullanıcı kendi hesabını ve cihazının özel WebView oturumunu kullanır; pakette kullanıcı bilgisi bulunmaz.

Mevcut bağımsız Play1.2.7 ve Arcana1.1.43(75), eklenti ayar düğmesini sunmuyor. Bu sürümlerde misafire açık bölümler oynatılır; üyelik girişi arayüzü varmış gibi sunulmaz.

## Derleme

JDK17, Android SDK37, Gradle9.8.1, AGP9.4.1, Kotlin2.4.21 ve pinned Cloudstream SDKd9c55e2e5b. ANDROID_HOME veya kendi local.properties dosyanızı ayarlayın. Bu klasörde bash gradlew :provider:assembleRelease kullanın. Root tools/build.py kaynak değişiminde sürümü ayırır; AAR/classes.jar AGP9.4.1 D8 ile min26 Android37/compile-api classpath kullanılarak derlenir. CS3 yalnız kök manifest.json/classes.dex içerir. Bağımlılıklar compile-only; Android host sağlar. requiresResources=false.

GPL-3.0. Derleme için kullanılan Jsoup jarı MIT bildirimini META-INF/jsoup/LICENSE içinde taşır; kaynak ve derleme girdileri bu klasördedir.
