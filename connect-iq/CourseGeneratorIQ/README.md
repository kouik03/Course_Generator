# Course Generator IQ — champ Garmin Fēnix 9 Pro 51 mm

**OBJECTIF PRIORITAIRE (NON ENCORE IMPLÉMENTÉ) :** suivre la trace GPS embarquée dans les blocs Base64 (comme TR1), projeter la position sur le parcours officiel, détecter les sorties de route et les ravitos automatiquement, calculer la distance et les temps restants depuis le tracé et le D+ réel. Coefficient configurable sur montre + téléphone : **100 m de D+ = 1 km plat équivalent** par défaut. **Voir [SPEC_GPS_TRACE.md](SPEC_GPS_TRACE.md)** pour les critères de validation et l'ordre des travaux. L'implémentation actuelle utilise encore la distance d'activité Garmin et ne fournit PAS ces garanties.

Ce sous-projet est un **champ de données Connect IQ** (pas une application autonome).
Le module est développé sur `experiment/terrain-debiased-curve-v1` sans modifier le Java/Maven de Course Generator.
**Statut : code poussé, non encore recompilé sur le SDK macOS 9.3.0 après ces ajouts.**

## Démarrer

1. Ouvrir `connect-iq/CourseGeneratorIQ` comme **dossier VS Code**.
2. `Monkey C: Select Device` → `fenix9pro51mm`.
3. `Monkey C: Run`. L'écran doit d'abord afficher **CG IQ / DEMO**.
4. Pour un vrai roadbook, remplir les trois champs Base64 dans les réglages Connect IQ.

Le fichier `resources/drawables/launcher.png` (64×64) est désormais dans le dépôt.

## Déjà implémenté

- Décodeur binaire offline **CGIQ v1**, trois blocs Base64, version et somme de contrôle.
- 1 à 25 points avec nom, distance, D+ et D− cumulés, temps planifié, pause, coupure horaire, coordonnées GPS optionnelles.
- Calcul de la **marge prévisionnelle** à la prochaine barrière (départ ou arrivée du ravito), temps de pause inclus.
- Heure cible, ETA estimée, allure moyenne de déplacement requise jusqu'à la barrière, D+ restant approximatif.
- Ajustement de l'ETA suivant l'efficacité constatée sur le segment depuis le ravito précédent, borné entre 0,65 et 2,2.
- **LAP manuel** : arrivée dans le ravito puis départ au LAP suivant. Auto Lap ignoré via `onTimerLap2`.
- Détection GPS facultative : arrivée lente et stable proche du point et départ par éloignement.
- Pause chronométrée, vibrations à l'approche de la fin de pause et au terme prévu.
- Sauvegarde de la progression et recalage kilométrique à chaque point, écran compact et plein écran.
- Départ personnel +0 à +180 min **sans décaler les barrières officielles**.

## Générer les trois blocs sur Mac

Le générateur offline (Python standard, aucune bibliothèque externe) utilise actuellement un fichier **JSON intermédiaire**.

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 tools/encode_roadbook.py tools/demo-roadbook.json
python3 tools/encode_roadbook.py tools/demo-roadbook.json -o /tmp/cgiq-blocs.txt
```

Le terminal affiche **BLOC 1**, **BLOC 2**, **BLOC 3**. Copier **seulement les chaînes Base64** dans les champs correspondants de Garmin Connect IQ / Garmin Connect, sans la mention « BLOC ».

Dans le simulateur : **File → Edit Persistent Storage → Edit Application.Properties data**, puis modifier les propriétés et relancer l'activité.

### JSON de référence

```json
{
  "name": "DEMO FICTIVE",
  "official_start": "07:30",
  "points": [
    {
      "name": "R1",
      "km": 12.35,
      "ascent_m": 330,
      "descent_m": 210,
      "target_elapsed": "02:15",
      "pause_min": 10,
      "cutoff": "10:30",
      "cutoff_after_pause": true,
      "lat": 43.85,
      "lon": 4.36
    },
    {
      "name": "FIN",
      "km": 87.90,
      "ascent_m": 2269,
      "descent_m": 2361,
      "target_elapsed": "16:53",
      "pause_min": 0,
      "cutoff": "26:45",
      "cutoff_after_pause": false
    }
  ]
}
```

**Ces points sont illustratifs, pas les données officielles de Veni Vici.**

- `official_start` : départ officiel HH:MM. Le délai personnel est réglé séparément.
- `target_elapsed` : passage planifié **en temps écoulé depuis le départ officiel**, pauses antérieures incluses.
- `cutoff` : heure locale officielle, **pas** une durée. `26:45` signifie 02:45 le lendemain. `null` signifie pas de barrière.
- `cutoff_after_pause` : `true` si la limite s'applique au départ du point, donc pause décomptée.
- `ascent_m` et `descent_m` : dénivelés **cumulés**. Les coordonnées GPS sont facultatives, mais nécessaires pour détecter le ravito automatiquement.
- `km` : distance cumulée officielle arrondie à 10 m. Noms : ASCII sur 18 caractères maximum (accents retirés).

## Paramètres du champ

| Clé | Valeur / rôle |
| --- | --- |
| Block1, Block2, Block3 | Trois parties du même Base64 |
| StartDelay | Décalage de départ 0 à 180 minutes |
| RestAlertMin | Vibration avant fin du ravito, 0 à 30 minutes |
| AutoGps | Arrivée/départ par GPS, activé par défaut |
| ManualLap | LAP manuel arrivée/départ, activé par défaut |
| GpsRadius | Rayon 50 à 300 m, valeur initiale 100 m |

## Tests

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Tests à réaliser sur le simulateur / la montre :
1. Sans blocs : **CG IQ / DEMO** et premier ravito à 5 km.
2. Avec les blocs générés depuis `demo-roadbook.json` : affichage du plan réel chargé.
3. Simulation d'une activité en mouvement : diminution de la distance, ETA et marge.
4. Un LAP manuel : ravito ; un deuxième : point suivant.
5. Un Auto Lap : **ne doit pas** modifier le point courant.
6. Pause : vibrations avant et à la fin ; départ par GPS ou LAP.
7. Un décalage de départ +15 min : les BH restent fixes et la marge diminue de 15 min.
8. Une lettre Base64 modifiée : erreur de contrôle, pas de fausses données.
9. Sortie/retour sur le champ : progression conservée.

## Limites importantes

- **Pas encore de test compile/terrain** de ce lot. Les erreurs du compilateur local, s'il y en a, doivent être corrigées avant une sortie.
- La projection utilise la **distance de l'activité Garmin** recalée aux ravitos. Pas encore de projection de position sur la trace GPX.
- Le **D+ restant** est estimé linéairement dans la section ; il ne reflète pas une analyse d'altitude point par point.
- La vitesse terrain et la fatigue avancées du moteur Java **ne sont pas encore exportées** dans le protocole v1. Le correctif de vitesse en cours de segment reste approximatif, notamment en forte descente.
- La vibration doit être testée lors de la mise en veille/changement d'écran.
- **L'export direct JSON/Base64 depuis l'éditeur Java Course Generator reste à intégrer.** Pour cette tranche, l'encodeur Python fournit un protocole stable et testable, sans supposer le contenu du roadbook réel.
- Le mode démo est fictif, jamais un plan officiel. Ne pas utiliser la marge affichée comme garantie de passage.

## Diagnostic de lecture Base64 (simulateur Garmin)

En cas de chaine non reconnue, l'ecran affiche :

- **BLOC n / POS m** : bloc et position du premier caractere invalide (a partir de 1).
- **CHAR U+XXXX** : code Unicode du caractere effectivement lu par la montre (par ex. U+0022 = guillemet, U+005B = crochet, U+00A0 = espace insecable).
- **LONGUEURS 40/40/40** : tailles des trois valeurs effectivement recues depuis les parametres.
- **DECODEUR OK** : le decodeur a accepte un vecteur de test integre de 120 caracteres / 88 octets, identique au roadbook demo genere. Si ce test est OK et les valeurs utilisateur sont refusees, le probleme se situe dans la transmission/saisie des reglages.

L'application refuse le plan si sa somme de controle n'est pas bonne : **aucune extrapolation sur un roadbook endommage**. Les deux alphabets Base64 standard et URL-safe sont acceptes.

## Affichage 51 mm : tableau de bord de progression

La vue plein écran est inspirée des **principes d'ergonomie** des champs de données TrailRace+ : chiffres lisibles en mouvement, progression visuelle et points de contrôle sur la ligne, sans réutiliser ses ressources graphiques.

- **En-tête** : nom du prochain point de contrôle, sans préfixe doublé.
- **Deux grandes valeurs** : distance en km jusqu'au prochain point (à gauche) ; **marge prévisionnelle** avec couleur verte/ambre/rouge (à droite). La barrière horaire affichée au-dessus reste une heure **officielle**, inchangée par un départ personnel retardé.
- **Ligne ETA / PLAN** : ETA recalculée par le moteur actuel, face au passage cible du roadbook.
- **Barre de progression** : section bleu clair déjà parcourue, points pour les ravitaillements et l'arrivée, petit point blanc pour la position courante.
- **Deux lignes de prochains points** : noms et distances restant à parcourir depuis la position actuelle, et non les distances cumulées.
- **Bas** : allure moyenne du tronçon (elle reste inconnue avant 400 m parcourus) et allure requise jusqu'à la prochaine barrière, puis D+ restant approximatif / ETA arrivée.
- **Pause au ravito** : le grand chiffre gauche devient la durée de pause restante ; la ligne ETA devient une indication du bouton LAP pour repartir.

### Modes de la barre de progression

Dans les paramètres Connect IQ : **Barre de progression**.

| Mode | Description |
| --- | --- |
| **Parcours entier** | Distance du départ à l'arrivée, avec points de tous les ravitaillements |
| **Entre ravitaillements** | Portion entre le point précédent et le prochain, avec la progression locale |
| **Automatique (défaut)** | Parcours entier puis, dans le dernier kilomètre avant le prochain point, zoom sur ce dernier kilomètre avec repères tous les 100 m |

La barre utilise la distance **de l'activité Garmin recalée au passage des ravitos**, pas une localisation projetée sur la trace GPX comme TrailRace+ PRO. La précision du zoom reste donc limitée par la précision kilométrique de Garmin et par les recalages.

### Vérifications sur la Fēnix 9 Pro 51 mm

1. Charger les trois blocs de démonstration existants, sans les modifier.
2. Vérifier la nouvelle présentation : **R1 DEMO**, 5,00 km, +17 min, ETA 08:18, BH 08:40, points R1 / R2 / FIN et point blanc au départ.
3. Dans les réglages du simulateur, changer **Barre de progression** entre les trois modes. Le mode automatique doit être visible en simulant moins de 1 km avant R1 (ex. position 4,5 km).
4. Simuler une activité qui avance : le remplissage bleu clair et le point blanc progressent, les distances des deux lignes diminuent.
5. Simuler un LAP manuel d'arrivée au R1, puis le suivant pour repartir : le grand chiffre gauche affiche la pause, puis le prochain R2.
6. Vérifier le cadran rond mais aussi un écran 1/2 champ (affichage compact simplifié).
7. Vérifier qu'aucune valeur ne se coupe contre le bord du cadran.

**Ce lot d'affichage reste à compiler et vérifier dans le simulateur SDK 9.3.0**. Les GitHub Actions existantes ne compilent pas le code Monkey C.


## Une seule commande macOS : CGIq

Le script **run-cqiq.sh** est versionne avec les sources. Il utilise le depot existant sur `experiment/terrain-debiased-curve-v1` et cible `fenix9pro51mm`.

Installation unique dans zsh :

```bash
cd ~/Desktop/Course_Generator
git pull --ff-only origin experiment/terrain-debiased-curve-v1
bash connect-iq/CourseGeneratorIQ/install-cqiq.sh
source ~/.zshrc
```

Ensuite, **dans n'importe quel dossier** :

```bash
CGIq
```

La commande enchaine :
1. Verification de la branche courante, puis `git pull --ff-only` (aucun `reset --hard`, aucune suppression des modifications locales).
2. Recherche du SDK Garmin macOS installe et de la cle `~/Library/Mobile Documents/com~apple~CloudDocs/garmin/developer_key`.
3. Compilation de `CourseGeneratorIQ.prg` avec `monkeyc` pour la Fenix 9 Pro 51 mm.
4. **Uniquement si la compilation reussit**, fermeture des anciennes instances de ConnectIQ.app, lancement d'un simulateur neuf et execution avec `monkeydo`.

Le SDK peut etre force avec la variable `CGIQ_SDK=/chemin/du/SDK` si besoin. La commande ne modifie pas volontairement le stockage persistant des blocs Base64 du simulateur. Les fichiers d'installation shell sont lus par `bash`, donc les commentaires `#` n'entrainent pas l'erreur `zsh: command not found: #`.

## Affichage course minimaliste (inspire des principes de TrailRace+ PRO)

L'ecran plein champ n'affiche desormais **qu'un seul ravitaillement textuel** : le prochain point courant en haut. La barre conserve uniquement ses petits marqueurs de CP, sans liste de noms et de kilometrages sous celle-ci.

Du haut vers le bas :
1. Nom du prochain CP.
2. Distance restante / marge face a l'heure de barriere officielle (deux grandes valeurs).
3. Progression bleu clair de la course, marqueurs de CP, position blanche.
4. ETA recalculee et heure cible du plan, ou indication LAP pendant la pause.
5. Moyenne du troncon / allure requise pour la prochaine barriere.
6. D+ approximatif restant au prochain CP / heure estimee de fin.
7. **Heure locale et temps ecoule d'activite** sur la derniere ligne : `H 19:35 / T 03:21`. H = heure actuelle fournie par la montre (`System.getClockTime`), T = duree d'activite, pauses incluses. T ne revient pas a zero apres 24 heures.

Le champ **ne deplace pas les barrieres officielles** si le depart est retarde. L'horloge H est celle de la montre, sans la reconstituer a partir de l'heure de depart. Les donnees restees dans le roadbook sont reutilisees, seules les positions visuelles ont change.

**Test a realiser sur le SDK 9.3.0 :** lancer `CGIq`, verifier qu'aucune info en bas ne se chevauche, que l'horloge H reflete l'heure du Mac/simulateur, et que T avance quand l'activite est en cours.

### Correction du chevauchement ETA / barre et de l'horloge (Fenix 9 Pro)

Le simulateur du 12 novembre montrait la legende `0.0 / 18.0 km` superposee a `ETA 08:18 / PLAN 08:18`, ainsi qu'une heure de course tronquee et des titres D+/FIN trop longs. La disposition est maintenant en lignes distinctes : ETA au-dessus de la barre ; kilometre parcouru sous la barre ; deux allures ; D+ et FIN simplifies sur une seule ligne ; heure de la montre et temps de course remontes dans le cadran. Le rendu reste a valider sur le simulateur local avec `CGIq`.

## Nouvelle interface basee sur la photo reelle de TrailRace+ PRO (novembre 2026)

L'ecran plein format Fenix 9 Pro suit desormais la hierarchie visuelle fournie dans la photo : numero de CP, services eventuels, nom du ravitaillement et heure limite, **distance** et **temps restant avant barriere** en grands chiffres, progression et marge, allure / requis, denivele descendant au CP / estimation d'arrivee, heure actuelle et chronometre.

**Important :** le grand temps affiche a droite n'est **plus la marge previsionnelle**. La montre calcule le temps restant a la BH depuis le depart officiel, le retard de depart configure et le temps ecoule de l'activite. La *marge* estimee, deduisant les temps de progression et de pause necessaires, apparait sous la barre. Les deux notions sont volontairement distinctes.

Les barres affichent des pastilles pleines pour les CP deja passes et des cercles vides pour les prochains CP. Les icones de services ne sont affichees que lorsqu'elles sont declarees dans le roadbook, jamais devinees a partir du nom.

### Services des points : extension compatible CGIQ v1

Ajouter facultativement dans les objets `points` du JSON :

```json
"services": "WF"
```

- `W` = point d'eau (icone goutte).
- `F` = nourriture (icone couverts).
- `WF` = eau et nourriture, `""` = aucun service renseigne.

Le protocole binaire CGIQ v1 utilisait deja un octet de drapeaux : bit 0 = cutoff apres pause ; bit 1 = eau ; bit 2 = nourriture. Les blocs precedents restent valides et sont lus sans modification, mais **ils ne contiennent pas les services** et ne peuvent donc pas afficher les icones.

Pour comparer directement avec la nouvelle photo, un deuxieme roadbook fictif est fourni :

```bash
python3 tools/encode_roadbook.py tools/demo-services.json
```

Copier les nouvelles valeurs Bloc 1 / Bloc 2 / Bloc 3 dans les reglages Connect IQ. Pour voir rapidement les icones sans regeneration, vider les trois blocs : le mode `CG IQ / DEMO` integre contient de faux ravitaillements avec eau et nourriture, explicitement fictifs.

### Test visuel a realiser

Lancer `CGIq`, avec `fenix9pro51mm`. Verifier sur le simulateur :
1. En-tete CP1 et nom R1 DEMO au-dessus des grands chiffres.
2. **5,00 km** a gauche, **1:10 h** a droite (70 min avant BH 08:40 depuis le depart fictif 07:30), et **MARGE +17m** distincte sous la barre.
3. Barre de progression et marqueurs de ravitos, sans liste de noms.
4. **ALLURE** / **REQUIS**, puis **D- CP** / **ARRIVEE**.
5. Heure Garmin et chronometre de l'activite en bas.
6. Aucune information tronquee ou superposee. Tester aussi le cadran compact.

La demo a un depart officiel fictif a 07:30 : si le simulateur est lance a 20h, le compteur de BH reste exprime depuis le depart fictif et ne concorde pas avec l'horloge courante. En course reelle, il faut configurer exactement l'heure de depart et son decalage. **La compilation Monkey C et le rendu final de ce lot ne sont pas verifies par les workflows Python : ils restent a valider dans le simulateur macOS.**

Le D- CP est encore une **approximation lineaire sur le troncon**, et non le D- restant extrait de la geometrie GPX. Les pictogrammes et l'ergonomie sont une implementation propre a Course Generator IQ, sans reutilisation de graphismes TrailRace+ PRO.

## Reglages directement sur la montre : menus independants de Garmin Connect IQ mobile

La Fenix 9 Pro permet l'entree **getSettingsView()** pour les champs de donnees.
Ceci est une **interface differente** des fichiers de parametres XML editables
sur le telephone. L'ecran de la montre ne contient pas de champs Base64 :
les trois blocs sont toujours lus depuis l'application Garmin Connect IQ
sur le telephone, mais ne sont pas exposes dans le menu de la montre.

### Acces

Dans le profil d'activite qui contient le champ **Course Generator IQ**, ouvrir
les reglages de l'activite, puis l'entree **Connect IQ Fields / Champs Connect IQ**
et choisir **Course Generator IQ** (le chemin exact peut varier avec le firmware
Fenix 9 Pro). Dans le simulateur Garmin : **Settings -> Trigger App Settings**
lorsque le champ de donnees est charge. Si l'entree est grisee, verifier que
c'est bien le PRG nouvellement compile et que le champ est actif.

### Menu

1. **Depart en vague** : retards predefinis +0, +5, +10, +15, +20,
   +30, +45, +60, +90, +120 et +180 minutes ; ajustements +1/-1 et +5/-5
   minutes pour les vagues non rondes. **Reglage telephone** retire la
   surcharge de la montre et reprend `StartDelay` defini dans Garmin Connect IQ.
   Les barrières officielles **ne bougent pas** lorsqu'on change le retard.
2. **Consulter un CP** : liste dynamique des 1 a 25 CP du roadbook actuel,
   avec leurs vrais noms, km et D+ cumule. Choisir un point affiche une
   fiche **CONSULTATION** dans le champ, indiquant kilometres cumules,
   longueur du troncon, D+/D- du troncon, D+/D- cumules, distance
   depuis la position actuelle, pause, BH et passage prevu.
   Le **suivi de course continue normalement** pendant cette consultation,
   sans modifier le CP courant ou l'historique des pauses.
3. **Retour suivi live** : quitte la consultation pour retrouver
   l'ecran de course normal.
4. **Changer prochain CP** : selection d'un prochain CP, puis **confirmation
   obligatoire** pour reprendre le suivi sur cette cible. Cela change le
   prochain CP sans modifier la distance d'activite fournie par Garmin.
   Le curseur **virtuel du roadbook** est au minimum recale sur le
   ravitaillement precedent, puisque selectionner le prochain CP confirme
   les ravitos precedents. Cela **ne fabrique pas de point GPS ni de
   kilometrage dans le fichier FIT**. Si le curseur etait deja au-dela
   de ce ravito, il conserve sa position plus avancee ; revenir
   manuellement a un CP anterieur recale au point precedent.
   
### Persistance et securite

- Le retard applique sur la montre est conserve en stockage local et prime
  sur celui du telephone tant qu'il n'est pas reinitialise via
  **Reglage telephone**.
- La consultation est stockee separement et n'altere pas le CP courant,
  les LAP, les pauses ou le recalcule de la marge.
- Les choix de prochain CP comportent une confirmation et sont relies a la
  **signature du roadbook charge**, empechant le report d'une ancienne
  selection a un autre parcours.
- Une selection explicite faite avant le depart est preservee lors du
  demarrage initial de l'activite. Un reset d'activite remet le suivi a zero.
- Il faut sortir du menu pour revenir a l'ecran principal ; si un
  sous-titre du menu deja ouvert parait ancien apres un changement,
  quitter et rouvrir le menu.

### Plan de test montre / simulateur

1. Lancer `CGIq` puis **Settings -> Trigger App Settings**.
2. Confirmer que le menu ne comporte **aucun champ Base64**.
3. Choisir **Depart en vague -> +15 minutes** puis revenir au
   tableau de bord : la BH reste a 08:40 dans la demo et le temps restant
   diminue de 15 minutes. Le changement doit persister en rouvrant le
   champ. Choisir ensuite **Reglage telephone** pour revenir au retard 0.
4. Choisir **Consulter un CP -> CP2 R2 DEMO** ; sur l'ecran
   consultation, verifier 11 km cumules, 6 km de troncon et les D+/D-
   du troncon (280 m / 250 m). L'ecran de course doit toujours suivre R1.
5. **Retour suivi live** : retrouver R1 DEMO sans passage fictif.
6. **Changer prochain CP -> R2 -> Annuler** : rien ne change.
   Recommencer puis **Confirmer** : R2 devient le prochain CP.
7. Charger de nouveaux blocs, puis verifier qu'une consultation et un
   recalage anciens ne sont plus appliques a tort.

**Important : cette interface Monkey C doit encore etre compilee et testee
sur ton SDK Connect IQ 9.3.0.** Les tests Python verifies sur GitHub
controlent le codec et les invariants de source, mais ne garantissent pas
que le menu soit visible sur le firmware exact de la montre.

### Pourquoi conserver CGIQ v1 pour l'instant

Le codec actuel est un **format de roadbook versionne**, pas un prototype
jetable, mais son encodeur Python sert encore de **pont intermediaire** en
attendant l'export direct depuis Course Generator Java.

Le TR1 existant (routecode.py / routecode-adaptive.js) optimise un autre
contenu : geometry GPS simplifiee, profils d'altitude et points de controle,
dans trois champs allant jusqu'a environ 2000 caracteres chacun, avec
CRC16. L'encodeur adaptatif teste des budgets points/profils et protege les
points de controle dans la simplification ; cela peut reduire fortement la
taille a precision acceptable, mais ne prouve pas une compression
mathematiquement optimale de tout parcours possible.

CGIQ v1 conserve quant a lui les **temps cibles, pauses, D+/D- cumules,
BH avant/apres ravito et coordonnees optionnelles**, mais ne porte pas
encore les points de trace et de profil. L'integration future
spatiale/temps exige une etude de budget de bytes et un protocole compatible,
plutot que de substituer brutalement un des deux encodages.

## Correction mise en page ronde Fenix 9 Pro : novembre 2026

Le retour du simulateur montrait des chevauchements entre les lignes CP1/R1,
les valeurs ALLURE/REQUIS et les en-tetes D-/ARRIVEE, ainsi que des unites
tronquees ("k.."), une marge tronquee ("MARGE +17..") et le chronometre
coupe a droite.

Le tableau de bord utilise maintenant `Dc.getFontHeight()` pour calculer la
position des lignes : en-tete CP, nom et BH, deux grands chiffres, barre,
marge, ALLURE/REQUIS, D-/ARRIVEE, horloge et temps ecoule. Les marges
inter-lignes dependent des polices mesurees sur le peripherique.

La ligne redondante `0.0/18.0 km` sous la barre a ete retiree pour
recuperer de l'espace, et le footer est maintenant compacte au centre
(`21:03 | 0:00:00`) pour tenir dans la largeur disponible sur un cadran
rond. Les unites `km` et `h` ont des zones de texte plus larges.

Verifier le rendu avec `CGIq`. Les tests automatises Python ne compilent
pas Monkey C et ne peuvent pas confirmer visuellement l'absence
de chevauchements.

## Correctif menu noir et cadran coupe (fenix 9 Pro 51 mm)

Si **Settings -> Trigger App Settings** ouvrait un ecran noir dans le
simulateur, cela provenait probablement du renvoi direct d'un `Menu2` comme
vue de reglages d'un champ de donnees. Garmin a deja documente des anomalies
de rendu dans cette combinaison.

Le menu de la montre utilise maintenant un `WatchUi.View` et un
`WatchUi.BehaviorDelegate`, sans `pushView`. Le menu affiche une liste
personnalisee et offre les memes fonctions qu'avant. Aucun bloc Base64
n'est affiche ni editable depuis la montre.

Touches du menu : **UP/DOWN** pour defiler, **START/ENTER** pour choisir,
**BACK** pour revenir au menu principal puis quitter. En detail CP :
UP/DOWN change de CP, START garde ce CP comme **consultation** sans modifier
le CP actif. **Changer prochain CP** demande toujours une confirmation
supplementaire avant toute modification du suivi.

Le cadran de course utilise maintenant un placement **du bas vers le haut** :
l'heure actuelle et le chronometre ont une position reservee ; D-/ARRIVEE,
allures, marge, barre, grands chiffres et titre sont places au-dessus.
Si les polices Garmin sont plus hautes que prevu, la taille de l'allure est
reduite **avant** de sacrifier les donnees du bas. Il ne doit plus y avoir
d'ecran tronque au niveau de D-/ARRIVEE.

Recompiler et ouvrir le simulateur avec `CGIq`. Tester dans cet ordre :
1. Affichage plein ecran (D- CP, ARRIVEE et horloge visibles).
2. **Settings -> Trigger App Settings** : voir le menu personnalise plutot
   qu'un ecran noir.
3. Choisir **Depart en vague** +15 min puis revenir : l'heure de BH est
   inchangee, le compteur restant et la marge diminuent de 15 min.
4. Choisir **CONSULTER UN CP**, ouvrir CP2, passer a CP3 avec BAS, valider
   pour le visualiser sans changer le CP de suivi reel.
5. Choisir **CHANGER PROCHAIN CP** puis confirmer ou annuler ; valider le
   comportement des deux branches.

La validation automatique GitHub controle les fichiers de protocole, scripts
et contrats de source, **pas la compilation ou l'interface Monkey C**. En cas
d'ecran noir persistant, relever les traces d'execution du simulateur Garmin
plutot que de supposer que le menu a fonctionne.

### Progression apres selection manuelle du prochain CP

Exemple avec `demo-roadbook.json` : R1 est a 5 km, R2 a 11 km et
la ligne d'arrivee a 18 km. Si tu choisis **FIN comme prochain CP** dans
les reglages de la montre, le roadbook considere R1 et R2 comme deja
franchis. La barre totale devient orange **jusqu'a R2 = 11/18 km,
soit 61,1 %**, le curseur blanc apparait sur R2 et il reste **7 km**
jusqu'a FIN, meme si le simulateur ne fournit encore aucun kilometre
d'activite Garmin.

L'offset du roadbook est enregistre dans `routeOffsetM` et reste
distinct de `Activity.Info.elapsedDistance`. Cela ne falsifie jamais la
trace GPS Garmin. En presence d'une distance d'activite deja plus
avancee que R2, la selection de FIN ne doit pas revenir en arriere.

La selection **Consulter un CP** reste strictement en lecture seule :
elle ne remplit pas la barre. Les pastilles remplies correspondent
maintenant a la distance du roadbook confirmee plutot qu'au seul
numero du CP selectionne. L'effet est visible dans le mode de barre
**Parcours entier** (ou Automatique hors du zoom a 1 km) ; le mode
**Entre ravitaillements** affiche seulement le troncon courant.

## Deux moteurs de menus et vraie sortie des reglages (12 novembre 2026)

### Deux commandes pour le simulateur

```bash
CGIq
CGIq menu2
```

- **`CGIq`** : compile `monkey.jungle` avec l'interface personnalisee
  `WatchUi.View + BehaviorDelegate`, deja validee pour l'affichage dans
  le simulateur fenix9pro51mm.
- **`CGIq menu2`** : compile `monkey.native.jungle` avec l'interface
  native **`WatchUi.Menu2 + Menu2InputDelegate`** et l'execute dans le
  **meme simulateur**, pour pouvoir comparer les deux avant installation.
  Sur la Fenix reelle, la variante a utiliser est le Jungle
  `monkey.native.jungle`.

**Pourquoi un choix de compilation et non une detection automatique ?**
L'API publique Garmin n'expose pas de propriete fiable
`System.isSimulator`. Les anciennes astuces basees sur la version
du firmware ne sont pas fiables sur le SDK recent. Les annotations
d'exclusion de Jungle choisissent donc exactement une implementation
de `getSettingsView()` a la compilation.

### Fonctionnement du menu

Les deux menus utilisent **les memes donnees persistees** :

- Depart en vague (choix de la montre prioritaire sur celui du telephone).
- Consulter un CP, sans changer le suivi reel.
- Choisir le prochain CP avec confirmation.
- Retour au suivi : efface la consultation et demande la fermeture de
  l'ecran de reglages.

**Confirmer le nouveau CP** ou **consulter un CP pour l'afficher** demande
egalement la sortie des reglages. Nous utilisons `WatchUi.popView()`,
methode de navigation systeme. Si le firmware refuse cette action,
le probleme est inscrit dans le journal du simulateur, sans utiliser
`System.exit()` qui arreterait le champ de donnees. La fermeture du
menu sur l'appareil reste **a valider** : les tests Python de CI
n'executent pas le simulateur Garmin.

### Corrections ergonomiques

Le tableau de bord utilise maintenant une barre bleu-gris, un trait
bleu clair pour la distance deja parcourue, des **cercles bleus vides**
pour les CP a venir, des **cercles pleins** pour les CP franchis et un
curseur blanc pour la progression virtuelle du roadbook. Les CP
selectionnes manuellement comme deja passes sont inclus.

L'en-tete CP / nom / BH commence **dans la zone visible en haut** du
cadran. Les informations du bas conservent leur place en reduisant
les polices secondaires si besoin ; la distance et le delai officiel
restent prioritaires.

Les menus ont des titres et des pieds raccourcis :
`CG IQ REGLAGES`, `CONSULTER CP`, `CHOISIR CP SUIVANT`,
`RETOUR AU SUIVI`. Les noms du roadbook ne sont plus precedes
d'un numero CP redondant : `R1 DEMO` au lieu de `CP1 R1 DEMO`.
Les textes reellement trop longs reçoivent une troncature explicite
en `..` au lieu de disparaitre silencieusement.

### Essai d'acceptation

1. `CGIq`, puis **Settings > Trigger App Settings**.
2. Regarder les quatre titres de menu : aucun nom ne doit etre coupe.
3. Consulter R2, puis Retour/Back, puis **Retour au suivi** :
   le champ normal doit reaparaitre, avec le bon CP actif.
4. Changer prochain CP vers FIN et confirmer : l'ecran des reglages
   doit se fermer et la barre afficher 11/18 km (61 %) et 7 km restants.
5. `CGIq menu2`, puis refaire les tests 2 a 4 avec le **Menu2 natif**.
6. Sur un vrai appareil, compiler avec `monkey.native.jungle` et
   verifier les memes comportements. Ne pas supposer l'execution reelle
   validee par le simulateur.

La distance reste **la progression virtuelle du roadbook**, differente
de la distance mesuree et enregistree dans le FIT Garmin.


## Correctif ciblé de l'en-tête rond (capture du simulateur du 12 novembre 2026)

L'écran principal affichait les valeurs et la barre correctement, mais ne
montrait plus que les fragments du nom du CP (par exemple \`..MO\`) et de
la barrière horaire (\`BH..\`) tout en masquant le titre \`CP1/FIN\` :
les premières lignes étaient trop proches du bord **arrondi**.

La disposition plein écran utilise maintenant dix rangées verticales fixes,
de 14,5 % (titre CP) à 83,5 % (horloge/chrono), en réservant notamment
**24 %** au nom/BH et **33,5 %** aux grandes valeurs. Le texte de l'en-tête
est borné par la **corde disponible dans le disque** pour chacun des deux
centres de colonne et peut descendre d'une taille de police lorsque nécessaire.
Les fonctions \`Dc.getFontHeight\` limitent la taille de chaque police à
l'espace entre deux rangées.

Les autres informations (progression, marge, allures, D− et arrivée, horloge)
gardent leurs emplacements. Le moteur de calcul, le codec Base64, les
paramètres et le choix de Menu2 ne sont pas modifiés par ce correctif.

Après \`CGIq\`, vérifier :
1. **CP1/FIN** et icônes visibles au-dessus du nom/BH ;
2. nom du prochain CP lisible à gauche et heure BH complète à droite ;
3. grands compteurs, barre et marge toujours sans chevauchement ;
4. D−/ARRIVEE et heure/chrono toujours entièrement visibles ;
5. même rendu du cadran après \`CGIq menu2\` (seul le menu change).

Les tests GitHub vérifient les dix emplacements et les mécanismes de largeur
et de hauteur ; ils ne remplacent pas une validation **visuelle dans le
simulateur Garmin**.
