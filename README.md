# ydiff
The yaml diff tool.

## Install
1. install Java
2. install Ammonite with scala 3
    ```
    sudo sh -c '(echo "#!/usr/bin/env sh" && curl -L https://github.com/com-lihaoyi/Ammonite/releases/download/2.5.5/3.2-2.5.5-17-df243e14) > /usr/local/bin/amm3 && chmod +x /usr/local/bin/amm3' && amm3
    ```
   You will enter into a REPL, if the installation is successful. Then you can enter 'exit' to quit.
3. install ydiff
    ```
    tag=$(curl https://api.github.com/repos/zhranklin/ydiff/releases/latest -s|grep tag_name|sed 's/.*tag_name": "//g; s/",.*//g')
    sudo sh -c "wget -O - https://github.com/zhranklin/ydiff/archive/$tag.tar.gz | tar xzO ydiff-$tag/ydiff > /usr/local/bin/ydiff && chmod +x /usr/local/bin/ydiff"
    ```

**Tips**: Ammonite will fetch dependencies (by coursier) when you use ydiff first time, which takes a few minutes.
To accelerate this process, you can specify your maven repository in `COURSIER_REPOSITORIES` environment, e.g.:

```
export COURSIER_REPOSITORIES='https://maven.aliyun.com/nexus/content/groups/public|sonatype:snapshots|sonatype:releases'
```

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
