这是一个轻量级的 Minecraft 服务端管理面板。支持在 Docker 内运行，且自带插件系统。（至少有......对吧？）

语言：Java 8，HTML和JavaScript

使用方法：
你需要先安装java，我们的面板目前由java 8编译，所以你需要下载java 8及以上的java版本
如果你是Windows，你可以从[java.com](https://java.com)下载
如果你是Liunx，你可以运行以下命令安装java：
Debian & Ubuntu：

    ```bash
    sudo apt install openjdk-8-jdk

然后你从Releases把jar文件下载下来之后，然后运行以下命令运行：

    ```bash
    java -jar mc-panel.jar

如果你想自己编译的话，你从Code页面把代码下载下来之后，解压到一个文件夹里面
然后准备java环境，和上面的一样，但是不同的是，你需要下载Maven
如果你是Windows的话，你去这里下载：[Maven下载链接](https://maven.apache.org/download.cgi)
关于怎么配置你可以去网上搜索"怎么配置Maven"
Liunx的话相对简单，只要在终端输入以下命令
Debian & Ubuntu：

    ```bash
    sudo apt install maven

然后打开你们操作系统的终端，进入你解压后的mcpanel源代码文件夹，输入以下命令

    ```bash
    mvn package

然后它就会自己进行编译和打包了
等它编译完成之后，你的目录下会出现一个target文件夹，进入之后，里面的mc-panel.jar就是本体了
然后把这个jar放到一个空文件夹中，用上面的命令运行即可

怎么连接：
本地连接的话，打开你的浏览器，输入localhost:8080即可，如果你是局域网连接的话，就是你的局域网IP加上:8080
如果你想要在家外面，也就是让别人也来访问的话，你需要一个公网IP，或者内网穿透，内网穿透的话把映射设在8080即可
如果你是云服务器，那就是最简单的，运行jar之后，你只要在浏览器输入你的公网IP后面加上8080即可

具体的使用的话，我认为我的UI是通俗易懂的，嘿嘿

面板插件和实例插件的开发的话，你可以查看源代码中的src/main/resources/web，里面有两个txt文件，里面是内置的插件开发教程，AI写的（但是这个README.md是我自己写的，真的！），如果你想开发插件可以看看这个
未来我们会尝试支持mcdreforged插件，emmmmmm虽然不知道得到什么时候

开源协议：MIT License
不遵守这个我把你头剁下来qwq（假的）

---- xiongjianqi
