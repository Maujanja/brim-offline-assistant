# Msaidizi – amri za sauti kwa simu (Android)

Inasikiliza neno la kuamsha nyuma ya pazia, kisha inatekeleza amri yako.
**Haihitaji AccessKey wala akaunti yoyote** (STT API ni hiari, kwa ujumbe wa SMS).

## Kupata APK
GitHub Actions inajenga APK kila ukisukuma kwenye `main` (au **Actions > Build APK > Run workflow**).
Pakua `Msaidizi.apk` kwenye **Releases > Msaidizi (latest build)**. Usaini ni thabiti, kwa hiyo masasisho
yanasakinishwa juu ya toleo la zamani.

## Mipangilio ya mara ya kwanza (ndani ya app)
1. **Anza kusikiliza**: ruhusu maikrofoni, anwani, kupiga simu, SMS, hali ya simu, muziki na arifa.
2. **Washa Accessibility**: huu ndio ufunguo wa kufungua apps/kamera/dialer kutoka nyuma bila kugusa arifa,
   na kugusa swichi ya data. Kwenye Android 13+, ikikataa: Settings > Apps > Msaidizi > nukta tatu >
   *Allow restricted settings*, kisha jaribu tena.
3. **Ruhusu kufunga simu kwa sauti** (hiari): Device admin, kwa amri ya "zima simu".
4. **STT API** (hiari): angalia chini.

## Jinsi inavyofanya kazi
1. **Vosk** (modeli ndogo ya Kiingereza ndani ya APK) inasikiliza wake word kwenye simu yenyewe, bila intaneti.
2. Kikisikika: mlio mfupi, kisha amri moja inasikilizwa (utambuzi wa simu, au STT API yako).
3. `CommandHandler` inatekeleza. Kwa SMS, Msaidizi anasema "Sema ujumbe wako", mlio, anasikiliza ujumbe,
   kisha anautuma. Mwishoni Vosk inaanza kusikiliza tena.

## Neno la kuamsha
Lazima liwe maneno ya Kiingereza yaliyomo kwenye kamusi ya modeli: "hey phone", "hey computer", "hello jarvis".

## Amri
| Sema (baada ya mlio) | Kinachotokea |
|---|---|
| washa / zima tochi | Tochi |
| volume juu / chini / kimya | Sauti |
| saa ngapi | Inasema muda |
| fungua kamera / WhatsApp / app yoyote | Inafungua (kupitia Accessibility) |
| fungua wifi / bluetooth / mipangilio | Inafungua mipangilio |
| washa data sim 1 / zima data sim 2 | Inafungua paneli ya intaneti na kugusa swichi ya data ya SIM husika* |
| zima simu / funga simu | Inafunga skrini (Device admin au Accessibility) |
| piga mama / piga mama sim 2 / piga 0712… | Inapiga moja kwa moja (TelecomManager) |
| tuma ujumbe kwa mama (kisha sema ujumbe) | Inatuma SMS moja kwa moja; ongeza "sim 2" kwa SIM ya pili |
| cheza wimbo wa diamond / cheza muziki | Muziki wa kwenye simu; "unaofuata", "simamisha", "endelea", "acha muziki" |

\* Android haziruhusu app kuwasha data kwa API. Msaidizi anaiga mguso wako kwenye paneli ya mipangilio
(jitihada bora; muundo wa paneli hutofautiana kwa kila simu). Ikishindwa, anakwambia ugonge mwenyewe.

## STT API kwa ujumbe wa SMS
Kwenye skrini kuu, sehemu ya **STT API**: weka URL, API key na jina la modeli (vitufe vya OpenAI/Groq vinajaza
URL na modeli). Inafanya kazi na API yoyote inayolingana na OpenAI `POST /audio/transcriptions`
(multipart: `file`, `model`, `language`). Bonyeza **Jaribu STT** kuthibitisha. Kisanduku cha
"Tumia API kwa amri zote pia" kinaipa API kazi ya amri pia, si ujumbe tu (usahihi bora kwa Kiswahili).
Ufunguo unahifadhiwa kwenye simu yako tu. Usipoweka API, simu inatumia utambuzi wake wenyewe.

## Mambo ya kujua
- SMS inatumwa **moja kwa moja bila kuthibitisha**, na jina linatafutwa kwenye anwani kwa ulinganisho
  wa karibu (alama ya chini kabisa inakataliwa). Msaidizi anasoma ujumbe aliotuma ili ujue.
- Vosk si injini maalum ya wake word, kwa hiyo makosa ya uongo au kukosa yanaweza kutokea.
- Simu kama Tecno, Infinix, Xiaomi, Samsung huua huduma za nyuma: zima "Battery optimization" kwa app hii.
- Keystore ya `app/msaidizi-debug.jks` ni ya majaribio/matumizi binafsi tu. Usiitumie kuchapisha app kwa umma.
