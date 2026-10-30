# Friend Tracker Rule Repositories

Friend Tracker Rule Repositories are a way to share your custom friend tracker rules with others. You can create a repository with your rules and share the URL with others to import them into Purrfect.

## Repository Structure

A repository is a simple collection of files hosted on a web server or a Git repository. The root of the repository must contain an `index.json` file.

### `index.json` Format

The `index.json` file contains a list of all the rules in the repository. It is a JSON array of objects, where each object represents a rule.

Here is an example of an `index.json` file:

```json
{
  "rules": [
    {
      "name": "Example Rule",
      "author": "Your Name",
      "description": "This is an example rule that does something.",
      "path": "rules/example_rule.json"
    }
  ]
}
```

#### Rule Object Properties

*   `name` (String, required): The name of the rule. This will be displayed in the catalog.
*   `author` (String, optional): The author of the rule.
*   `description` (String, optional): A short description of what the rule does.
*   `path` (String, required): The relative path to the rule's JSON file from the root of the repository.

### Rule File

The rule file itself is a standard Purrfect friend tracker rule JSON file. You can export an existing rule from Purrfect to get a template. The file must be a valid JSON file.
