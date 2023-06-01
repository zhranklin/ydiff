# ydiff
The yaml diff tool.

## Install
Just download from [The Latest Release](https://github.com/zhranklin/ydiff/releases/latest)

Choose by your platform. If there is no supported version for your platform and you have java installed, you can use `noarch` version.

## Usage
```bash
ydiff -h
Yaml Diff
Usage: ydiff.sc [options] [source] [target]

  -h, --help               Show this help.
  --k8s                    Treat yaml docs as kubernetes resources.
  --show-new               Show complete yaml text of new yaml docs.
  --show-removed           Show complete yaml text of removed yaml docs.
  --no-ignore              Don't use default ignore list.(k8s only)
  -m, --multi-lines-around <lines>
                           How many lines should be printed before and after
                           the diff line in multi-line string
  --extra-ignore <rule-text>
                           Extra ignore rules, can be specified multiple times.
  --extra-ignore-file <file>
                           Extra ignore rules file, can be specified multiple times.
  source                   Source yaml file, specify "<k8s>" to fetch resource
                           from kubernetes cluster, and default to be <k8s>.
  target                   Target yaml file, default to be stdin.
```

## Usage by docker

```
docker run --rm -v`pwd`:/wd zhranklin/toolbox:v0.1.7 ydiff /wd/file1.yaml /wd/file2.yaml
```
