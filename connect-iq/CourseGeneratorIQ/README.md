# Course Generator IQ — prototype

Prototype **Connect IQ Data Field** (pas une application autonome) pour Garmin Fēnix.

## Ouverture dans VS Code

Ouvrir le dépôt Course_Generator, puis ouvrir ce sous-dossier dans une fenêtre VS Code dédiée si l'extension Monkey C ne trouve pas `monkey.jungle`.

La branche de référence est `experiment/terrain-debiased-curve-v1` : ne pas changer de branche.

## Compilation

Avec le SDK Connect IQ déjà configuré dans VS Code :
1. Palette de commandes → **Monkey C: Select Device**.
2. Sélectionner une Fēnix 8 prise en charge par le SDK.
3. Lancer **Monkey C: Run** (ou **Run and Debug** selon la version de l'extension).

Le projet ne contient pour l'instant qu'un champ affichant le temps d'activité et la distance.

**Important :** prototype non encore compilé ni testé sur le SDK local. L'identifiant de produit `fenix8` du manifeste doit être vérifié dans le SDK installé et remplacé par la variante exacte si nécessaire. Le manifeste et ses ressources pourront nécessiter des ajustements après la première validation.

## Suite prévue

Paramètres Connect IQ (trois blocs), décodage du format CGIQ v1, progression, barrières, pauses et prédictions par segment.
