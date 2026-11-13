# Course Generator IQ — champ de données Garmin

Projet Connect IQ pour Fēnix 9 Pro 51 mm, sur la branche
`experiment/terrain-debiased-curve-v1` du dépôt Course Generator.
L'application reste un **champ de données d'activité** (pas une application
autonome), utilisable hors ligne. Le code Java/Maven de Course Generator
n'est pas modifié par la chaîne de compilation Connect IQ.

> **Objectif prioritaire, encore à développer :** embarquer le tracé GPS et
> le profil altimétrique dans les blocs Base64 pour projeter l'avancement
> sur la trace officielle, signaler les sorties de parcours, détecter
> automatiquement les CP et calculer le temps restant selon le D+ réel.
> L'équivalence de base sera **100 m D+ = 1 km plat**, réglable sur la
> montre et le téléphone. Voir [SPEC_GPS_TRACE.md](SPEC_GPS_TRACE.md).
> La version actuelle utilise encore la distance d'activité Garmin, plus
> un recalage virtuel à chaque CP confirmé.

## Compiler et lancer : une commande, deux PRG indépendants

Installation unique de la commande zsh (les scripts sont versionnés) :

```bash
cd ~/Desktop/Course_Generator
git pull --ff-only origin experiment/terrain-debiased-curve-v1
bash connect-iq/CourseGeneratorIQ/install-cqiq.sh
source ~/.zshrc
```

Ensuite, depuis n'importe quel dossier :

```bash
CGIq
CGIq alternate
```

Les deux commandes récupèrent les dernières modifications de GitHub, compilent
pour `fenix9pro51mm` et relancent le simulateur proprement. Le script
`run-cqiq.sh` n'écrase pas les fichiers locaux du dépôt et ne ferme pas le
simulateur précédent si la compilation échoue.

| Commande | Fichier compilé | Menu sur la montre |
| --- | --- | --- |
| `CGIq` | `bin/CourseGeneratorIQ-main.prg` | Garmin `Menu2` |
| `CGIq alternate` | `bin/CourseGeneratorIQ-alternate.prg` | Garmin `Menu2` |

**Important : les deux variantes utilisent exactement le même code source
et le même `monkey.jungle`**. `alternate` est seulement un **second emplacement
de PRG**, pour conserver une version compilée pendant les itérations ; il
ne constitue pas un autre moteur de menu ni une autre version du roadbook.
Recompiler l'un ne remplace pas le PRG de l'autre. Les anciens artefacts
`-custom.prg`/`-menu2.prg` créés pendant les essais précédents ne sont pas
utilisés par ces commandes.

Le seul menu maintenu est `CGIQNativeMenu.mc` (Garmin `WatchUi.Menu2` et
`Menu2InputDelegate`). L'ancien menu personnalisé et son Jungle ont été
retirés.

## Paramètres : téléphone contre montre

Sur téléphone, les réglages Garmin Connect IQ gardent les trois chaînes
`Block1`, `Block2` et `Block3`, le délai de départ, les alertes de pause,
la détection GPS et le mode de la barre. **Les blocs Base64 ne sont pas
éditables depuis la montre.**

Dans le simulateur : **Settings → Trigger App Settings**.
Sur la montre physique, ouvrir les réglages du champ Course Generator IQ
dans l'activité (chemin dépendant du firmware).

Menu Garmin `Menu2` :

- **Départ en vague :** 0 à 180 minutes, avec +1/−1 et +5/−5 ;
  revenir au réglage téléphone est possible
- **Consulter CP :** feuilleter les points et leurs distances,
  D+/D−, pauses et BH, sans modifier le suivi réel
- **Choisir CP suivant :** désigner le prochain CP, puis
  **confirmer explicitement** ; tous les CP précédents sont réputés
  franchis dans le roadbook
- **Retour au suivi :** effacer une éventuelle consultation et
  demander la fermeture des réglages

La persistance utilise le stockage Connect IQ, lié à la signature du
roadbook. La fermeture des paramètres avec `WatchUi.popView()` fonctionne
selon les possibilités du firmware et reste à valider sur montre réelle.

## Affichage principal et barre de progression

Inspiré de l'ergonomie visible sur la photo réelle de TrailRace+ PRO fournie
pour les essais : prochain CP, services eau/nourriture éventuels, nom et BH,
distance et compte à rebours officiels en grands caractères, barre, marge
prévisionnelle, allure et allure requise, D− restant approximatif, arrivée
estimée, heure courante et chronomètre.

**Bleu-gris** : fond de barre ; **bleu clair** : progression reconnue
(virtuelle dans la version actuelle) ; **cercles bleus évidés** : CP futurs ;
**cercles bleus pleins** : CP déjà franchis ; **anneau bleu et centre blanc** :
position courante du coureur. La barre peut afficher le parcours entier,
le tronçon courant ou un zoom automatique sur le dernier kilomètre.

La position courante est actuellement calculée depuis la distance Garmin
et l'offset virtuel de roadbook, **pas encore par projection sur la trace GPS**.
Choisir FIN après R2 dans le plan fictif de 18 km confirme R1 et R2 :
la barre avance au minimum à 11/18 km et il reste 7 km, sans falsifier
le FIT Garmin. Consulter un CP ne modifie pas cette progression.

L'interface utilise des lignes espacées et des contrôles de largeur tenant
compte du cercle de la Fēnix 9 Pro. L'absence de chevauchement doit toujours
être vérifiée dans le simulateur après chaque modification visuelle.

## Générer les blocs Base64 (format CGIQ v1 conservé)

L'encodeur Python actuel prend un **JSON intermédiaire**. Il est destiné à
être remplacé plus tard par un export direct de Course Generator Java, sans
modifier le format en cours de développement.

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 tools/encode_roadbook.py tools/demo-roadbook.json
python3 tools/encode_roadbook.py tools/demo-services.json
```

Copier uniquement les trois chaînes Base64 dans les propriétés correspondantes.
Sans blocs, le champ peut charger la démonstration fictive intégrée.
Le format CGIQ v1 porte les noms, distances officielles cumulées, D+/D−
cumulés, heures cibles, pauses, BH et coordonnées facultatives ; les services
`W` = eau, `F` = alimentation peuvent être déclarés par point dans le JSON.
Exemple minimal d'un point (illustratif) :

```json
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
  "lon": 4.36,
  "services": "WF"
}
```

`official_start` en racine représente l'heure de départ officielle. Un
`cutoff` tel que `26:45` signifie 02:45 le lendemain ; un `null`
signifie l'absence de barrière. Les BH ne bougent pas après modification
du délai de départ personnel. Noms ASCII de 18 caractères maximum dans le
protocole actuel. Les distances et dénivelés restent des valeurs **cumulées**.

## Tests et limites

Tests du codec, des invariants et des scripts :

```bash
cd ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ
python3 -m unittest discover -s tools -p 'test_*.py' -v
```

Les GitHub Actions valident les tests Python, **pas** la compilation Monkey C
ni le rendu du simulateur. Tester sur le SDK Garmin 9.3.0 et, à terme, la
montre réelle.

Parcours de validation immédiat : `CGIq`, vérifier les textes et la barre
sur Fēnix 9 Pro ; ouvrir `Trigger App Settings` ; consulter un CP puis
revenir au suivi ; choisir FIN puis confirmer ; contrôler les temps,
distances et marqueurs ; tester un départ retardé ; refaire les essais avec
`CGIq alternate` en conservant l'autre PRG.

**Limitations actuelles :** pas de polyligne GPX embarquée, pas de projection
GPS ni d'alerte « hors parcours » fiable ; D+/D− restants approximés par
interpolation linéaire entre CP ; ETA encore approximative sans profil de
terrain complet. Voir [SPEC_GPS_TRACE.md](SPEC_GPS_TRACE.md) pour la
spécification prioritaire et les tests à réaliser avant usage sur ultra.
