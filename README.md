# Msaidizi – amri za sauti kwa simu (Android)

Inasikiliza neno la kuamsha nyuma ya pazia, kisha inatekeleza amri yako.
**Haihitaji AccessKey wala akaunti yoyote.**

## Kupata APK
GitHub Actions inajenga APK kila ukisukuma kwenye `main` (au bonyeza **Actions > Build APK > Run workflow**).
Pakua `Msaidizi.apk` kwenye **Releases > Msaidizi (latest build)**.
Usaini ni thabiti, kwa hiyo masasisho yanasakinishwa juu ya toleo la zamani. Ukitoka toleo la kwanza
(lililokuwa na usaini tofauti), ondoa app ya zamani kwanza.

## Jinsi inavyofanya kazi
1. **Vosk** (modeli ndogo ya Kiingereza ndani ya APK) inasikiliza kifungu cha wake word kwenye simu yenyewe, bila intaneti.
2. Kikisikika: mlio mfupi, kisha **SpeechRecognizer** ya simu inasikiliza amri moja (Kiswahili au Kiingereza; inahitaji intaneti kwenye simu nyingi).
3. `CommandHandler` inatekeleza amri, kisha Vosk inaanza kusikiliza tena.

## Neno la kuamsha
Lazima liwe maneno ya Kiingereza yaliyomo kwenye kamusi ya modeli: "hey phone", "hey computer", "hello jarvis".
Vifungu vya maneno mawili vinapunguza makosa. Neno la Kiswahili (mfano "msaidizi") halitambuliwi na modeli hii.

## Amri
| Sema (baada ya mlio) | Kinachotokea |
|---|---|
| washa / zima tochi | Tochi inawaka / inazima |
| volume juu / chini / kimya | Inabadilisha sauti |
| saa ngapi | Inasema muda |
| fungua kamera (au "video") | Inafungua kamera* |
| fungua wifi / bluetooth | Inafungua mipangilio* |
| piga mama / piga 0712345678 | Inapiga kwa jina la anwani (phonebook) au kwa namba* |
| fungua WhatsApp (app yoyote) | Inafungua app kwa jina* |

\* Android 10+ hairuhusu app ya nyuma kufungua skrini. Ukiwa na "Display over other apps" inafunguka yenyewe.
Bila hiyo (mfano simu za Android Go), Msaidizi anatuma arifa: skrini ikiwa imefungwa inafunguka yenyewe,
ikiwa wazi unagusa arifa.

Kuongeza amri: ongeza tawi ndani ya `execute()` kwenye `CommandHandler.kt`.

## Mambo ya kujua
- Vosk ni utambuzi wa jumla, si injini maalum ya wake word, kwa hiyo makosa ya uongo au kukosa yanaweza kutokea
  kuliko injini za kibiashara. Tumia kifungu cha maneno mawili na ujaribu.
- Simu kama Tecno, Infinix, Xiaomi, Samsung huua huduma za nyuma: zima "Battery optimization" kwa app hii.
- Keystore ya `app/msaidizi-debug.jks` ni ya majaribio/matumizi binafsi tu. Usiitumie kuchapisha app kwa umma.
