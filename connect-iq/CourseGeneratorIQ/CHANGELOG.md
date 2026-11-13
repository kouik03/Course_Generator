# Historique Course Generator IQ

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
