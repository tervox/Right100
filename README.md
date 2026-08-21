<div align="center">

<img src="app/src/main/res/drawable/ic_launcher.png" width="120" alt="App icon"/>  
<img src="app/src/gplay/res/drawable/ic_launcher.png" width="120" alt="App icon"/>

# Right Gallery/Alright Gallery
<a href='https://play.google.com/store/apps/details?id=com.goodwy.gallery'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a>  <a href='https://play.google.com/store/apps/details?id=dev.goodwy.gallery'><img alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png' height=80/></a>
</div>

Your private moments are protected. Discover Right Gallery/Alright Gallery, where your privacy is our priority. <br><br>

## ☕ Support the Project

If you find **Right Gallery/Alright Gallery** useful and would like to support its development, consider
buying me a coffee! Your support helps me maintain and improve this project.

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://www.buymeacoffee.com/goodwy)

*Every contribution, no matter how small, helps keep this project alive and growing! ❤️*<br><br><br>


*Based on [Simple Gallery](https://github.com/SimpleMobileTools/Simple-Gallery), [Fossify Gallery](https://github.com/FossifyOrg/Gallery).*

## Build no Termux

O arquivo `local.properties` não deve ser versionado, pois o caminho do Android SDK varia entre computadores. No Termux, configure o SDK localmente antes de compilar:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
./gradlew :app:assembleFossDebug
```

A variante FOSS produz um APK de depuração sem assinatura de release. Para distribuição, configure as variáveis de assinatura documentadas no `app/build.gradle.kts` e utilize a variante correspondente.
