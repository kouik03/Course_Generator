# Historique Course Generator IQ

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
