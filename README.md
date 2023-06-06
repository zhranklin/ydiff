# ydiff
The yaml diff tool.

## Install
Just download from [The Latest Release](https://github.com/zhranklin/ydiff/releases/latest)

Choose by your platform. If there is no supported version for your platform and you have java installed, you can use `noarch` version.

## Usage
```bash
ydiff -h
Yaml Diff v0.1.6
Usage: ydiff [options] [source-file] [target-file]

  -h, --help               Show this help.
  -v, --version            Show version
  --k8s                    Treat yaml docs as kubernetes resources.
  --json-patch             Show kubectl patch commands.(k8s only)
  --show-new               Show complete yaml text of new yaml docs.
  --show-removed           Show complete yaml text of removed yaml docs.
  --only-id                Show only IDs for changed/removed/added docs
  --no-ignore              Don't use default ignore list.(k8s only)
  --no-inline              Show diff line by line.
  -m, --multi-lines-around <lines>
                           How many lines should be printed before and after
                           the diff line in multi-line string
  --extra-ignore <rule-text>
                           Extra ignore rules, can be specified multiple times.
  --extra-ignore-file <file>
                           Extra ignore rules file, can be specified multiple times.
  --dump <file>            Dump file name, if set, the resource of k8s source will be dumped to the file
  source-file              Source yaml file, specify "k8s" to fetch resource
                           from kubernetes cluster, and default to be k8s.
  target-file              Target yaml file, default to be stdin.
```

