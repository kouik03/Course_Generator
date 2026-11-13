# Historique Course Generator IQ

## 2026-11-13 — Dessiner les ravitos avant le coureur, sans effacement

- Correction du repère de départ qui disparaissait lorsque le marqueur
  blanc en sortait : l'ancien code peignait un cercle noir derrière le
  coureur, ce qui masquait le départ.
- Ordre d'affichage désormais **ligne → cercles de départ/CP →
  point blanc**, sans aucun disque noir ajouté sous le coureur.
- **Ravito futur :** cercle bleu évidé (centre noir).
- **Ravito franchi :** cercle bleu plein, exactement le même diamètre
  que les ravitos futurs ; il reste visible pendant et après le passage.
- **Position du coureur :** simple point blanc tracé par-dessus, qui
  laisse apparaître l'anneau bleu du ravito lorsque les deux coïncident.
- La logique supplémentaire de proximité destinée à dessiner un faux
  anneau autour du coureur est supprimée ; les vrais cercles des CP
  suffisent et restent ancrés à leur position.
- Le reste du cadran et les calculs de course ne changent pas.

**Validation :** tests GitHub et rendu local Garmin à contrôler
indépendamment.

## 2026-11-13 — Cercle conservé lorsque le coureur est au ravito

- Le marqueur du coureur reste un **point blanc seul entre deux CP**.
- **Sur le départ ou près d'un CP**, le point blanc conserve un
  **cerclage bleu**. Les autres repères de ravitaillement gardent leurs
  cercles évidés ou pleins selon leur état.
- Détection visuelle du chevauchement avec deux conditions : position
  du roadbook à 100 m maximum du CP **et** écart à l'écran de 8 pixels
  maximum. Une simple proximité visuelle sur la barre globale d'un
  ultra ne suffit pas à afficher à tort le cerclage.
- Aucun changement au calcul de progression ni au suivi GPS, ni aux
  paramètres Garmin.

**Validation :** tests de non-régression sur les cercles de départ,
les ravitos et le coureur. Rendu Garmin à vérifier dans le simulateur.

## 2026-11-13 — Repère de départ et curseur blanc simple

- Ajout d'un **cercle au départ (km 0)** sur la barre globale, comme pour
  les ravitaillements. Le départ est dessiné explicitement, car les points
  du roadbook ne contiennent que les CP et l'arrivée.
- Au départ, le repère est évidé ; une fois le coureur parti, il devient
  bleu plein comme les CP franchis.
- Le coureur est désormais représenté par **un point blanc simple**,
  sans anneau bleu. Si sa position se superpose exactement à un CP ou
  au départ, le point blanc prend la priorité visuelle, puis le repère
  réapparaît lorsque la progression le quitte.
- Aucun changement des distances, de la géométrie de la barre,
  des données de course, des paramètres ou du menu Garmin natif.
- Tests de régression ajoutés pour le repère km 0, les CP futurs évidés
  et le curseur blanc sans entourage.

**Validation :** tests GitHub réussis ; rendu Monkey C à confirmer sur
le simulateur Fēnix 9 Pro 51 mm.

## 2026-11-13 — CUT prioritaire et première ligne remontée

- Capture de référence : le groupe `CP2` + goutte d'eau pouvait remonter
  nettement, tandis que `CUT 09:55` était coupé (`CUT 09:..`).
- Le groupe CP + pictogrammes est déplacé de **9,9 % à 5,5 %** de la
  hauteur du champ ; le nom et le CUT passent de **19,2 % à 15 %**.
  Les grandes données de course, la barre et le footer ne bougent pas.
- Nouvelle fonction `drawCheckpointNameAndCut()` : mesure le texte
  complet de l'heure officielle et le **dessine sans aucune troncature**,
  aligné au bord droit utilisable du cadran circulaire.
- Le nom du ravitaillement reçoit **uniquement l'espace restant** et
  peut être raccourci avec `..`. En cas de largeur extrême, le nom
  disparaît avant d'amputer le CUT.
- Tests de contrat pour la priorité de CUT, la hauteur des deux rangées
  et l'absence de troncature de la barrière officielle.

**Validation :** tests Python GitHub réussis. La compilation Garmin SDK
et le rendu sur Fēnix 9 Pro restent à vérifier dans le simulateur.

## 2026-11-13 — En-tête compact inspiré de la capture TrailRace+ PRO

- En-tête principal sur **deux rangées seulement** :
  **CP1 / CP12 / CP22 + pictogrammes** sur la première ligne compacte
  centrée ; **nom du ravitaillement à gauche et CUT HH:MM à droite**
  sur la ligne inférieure, plus large grâce à la forme ronde du cadran.
- Titre CP en blanc, nom du ravito et heure limite en caractères
  volontairement plus petits. Les deux lignes sont remontées, sans
  toucher aux grandes valeurs, à la barre ou à l'horloge.
- Nouveau regroupement horizontal mesuré de CP et icônes. Le code réserve
  **cinq emplacements possibles** (y compris CP à deux chiffres) et
  réduit la police en fonction de la corde disponible en haut du cercle.
  Le rendu effectif reste limité aux services présents dans les drapeaux
  du roadbook. **Seuls eau et nourriture sont actuellement encodés** ;
  les trois autres emplacements sont des réserves graphiques, pas des
  services supplémentaires annoncés.
- Le préfixe `CUT` signifie toujours **l'heure officielle de barrière**
  (aucune modification des heures ni du calcul).

**Validation :** tests de contrat et de codec GitHub réussis. Compilation
Monkey C et contrôle visuel sur Fēnix 9 Pro 51 mm restent nécessaires.

## 2026-11-13 — Correction des compteurs principaux (capture 06:56)

- La capture Fēnix 9 Pro montrait `5.00` et `1:10` trop petits, séparés
  de `km` et `h` par de grands espaces. Le défaut provenait de deux
  contrôles indépendants : un redimensionnement excessif de la police
  principale et une position absolue pour chaque unité.
- Nouvelle fonction Monkey C `drawPrimaryMetric()` : mesure la largeur
  du nombre et de l'unité, centre leur **ensemble**, aligne l'unité plus
  petite au bas du nombre et ne réduit la police principale que lorsque
  la largeur ou la hauteur disponible l'exige réellement.
- La ligne des compteurs remonte légèrement ; la barre progresse un peu
  plus bas pour récupérer suffisamment de hauteur pour les grands chiffres.
  Le nom du CP, la BH, la marge, le bas de cadran et le menu `Menu2`
  restent inchangés.
- Tests de non-régression ajoutés pour l'alignement chiffres/unités et
  l'espace réellement réservé aux grandes polices.

**État de validation :** tests Python GitHub réussis ; compilation Monkey C
et rendu visuel à confirmer dans le simulateur Fēnix 9 Pro 51 mm.

## 2026-11-13 — Menu Garmin unique, deux PRG et interface de course

- **Menu :** adoption exclusive de `WatchUi.Menu2` et
  `Menu2InputDelegate` pour le simulateur et la montre.
  Suppression de `CGIQDeviceSettings.mc`, du moteur de menu personnalisé
  et du fichier `monkey.native.jungle`. Une seule méthode
  `getSettingsView()` est conservée.
- **Commandes :** `CGIq` produit
  `bin/CourseGeneratorIQ-main.prg` et `CGIq alternate` produit
  `bin/CourseGeneratorIQ-alternate.prg`, à partir du même code
  et du même `monkey.jungle`. Recompiler l'un ne remplace pas l'autre.
  Chaque commande synchronise GitHub, compile et relance le simulateur.
- **Barre :** maintien des cercles évidés bleu clair pour les CP à venir,
  des cercles pleins pour les CP validés, et affichage de la position
  courante par **un anneau bleu avec un centre blanc**.
- **Cadran :** nouvelle répartition verticale pour réduire les
  chevauchements entre marge, allure, allure requise, D−, arrivée
  et horloge, tout en préservant le nom du CP et la BH.
- **Documentation/tests :** réécriture du README pour supprimer
  les instructions obsolètes de l'ancien menu ; tests de non-régression
  des deux sorties PRG, de la sélection manuelle de CP et de la disposition.

**Validation :** les tests Python GitHub ont réussi. La compilation
Monkey C et l'apparence des dernières modifications restent à vérifier
localement avec le SDK Connect IQ et la Fēnix 9 Pro 51 mm.

## Suite prioritaire

Voir [SPEC_GPS_TRACE.md](SPEC_GPS_TRACE.md) : la progression par
projection GPS sur trace Base64, la détection hors parcours, la détection
automatique des ravitaillements et l'ETA dépendante du D+ réel
ne sont pas encore disponibles dans le prototype actuel.
