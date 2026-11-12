# Course Generator IQ — champ Garmin Fēnix 9 Pro 51 mm

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
- **Barre de progression** : section orange déjà parcourue, points pour les ravitaillements et l'arrivée, petit point blanc pour la position courante.
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
4. Simuler une activité qui avance : le remplissage orange et le point blanc progressent, les distances des deux lignes diminuent.
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
3. Progression orange de la course, marqueurs de CP, position blanche.
4. ETA recalculee et heure cible du plan, ou indication LAP pendant la pause.
5. Moyenne du troncon / allure requise pour la prochaine barriere.
6. D+ approximatif restant au prochain CP / heure estimee de fin.
7. **Heure locale et temps ecoule d'activite** sur la derniere ligne : `H 19:35 / T 03:21`. H = heure actuelle fournie par la montre (`System.getClockTime`), T = duree d'activite, pauses incluses. T ne revient pas a zero apres 24 heures.

Le champ **ne deplace pas les barrieres officielles** si le depart est retarde. L'horloge H est celle de la montre, sans la reconstituer a partir de l'heure de depart. Les donnees restees dans le roadbook sont reutilisees, seules les positions visuelles ont change.

**Test a realiser sur le SDK 9.3.0 :** lancer `CGIq`, verifier qu'aucune info en bas ne se chevauche, que l'horloge H reflete l'heure du Mac/simulateur, et que T avance quand l'activite est en cours.
