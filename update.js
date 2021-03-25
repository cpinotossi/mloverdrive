const fs = require('fs');
const fsPromises = require('fs').promises;
const debug = "";

var prop = {
    "xmx": "2g",
    "xms": "2g",
    "VM_NAME": "myPC",
    "PROBLEM_ROW": "150",
    "PROBLEM_COL": "5000",
    "IS_SERVICE_BUS": "false",
    "LOG_LEVEL": "info"
}

run("arm.deploy.output.json");
async function run(path) {
    await fs.readFile(path, 'utf8', (err, data) => {
        if (err) {
            console.error(err)
            return
        }
        let envString = "";
        let upperKey = "";
        const indentionKey = "           ";
        const indentionValue = indentionKey + "  ";

        const armOutputObject = (JSON.parse(data));
        //const appSettingValues = armOutputObject.properties.outputs;

        updateAppSettingValues(armOutputObject.properties.outputs).then(appSettingValues => {
            //console.log("appSettingValues:\n" + JSON.stringify(appSettingValues, null, '\t'));
            //Update JMeter Test Property File
            var jmeterMap = {
                "${STORAGE_INPUT_NAME}": appSettingValues.STORAGE_INPUT_NAME,
                "${STORAGE_INPUT_SASTOKEN}": appSettingValues.STORAGE_INPUT_SASTOKEN
            }
            updateMulti("./templates/jmeter.test.template.properties", jmeterMap, './jmeter/test.properties'+debug);
            //Update Visual Studio Code launch 
            var launchEnvObject = JSON.parse(JSON.stringify(appSettingValues));
            launchEnvObject.IS_SERVICE_BUS = prop.IS_SERVICE_BUS;
            launchEnvObject.PROBLEM_ROW = prop.PROBLEM_ROW;
            launchEnvObject.PROBLEM_COL = prop.PROBLEM_COL;
            launchEnvObject.VM_NAME = prop.VM_NAME;
            launchEnvObject._JAVA_OPTIONS = `-Xmx${prop.xmx} -Xms${prop.xms}`;
            var envMap = {
                "${env}": JSON.stringify(launchEnvObject, null, '\t')
            }
            updateMulti("./templates/vscode.launch.template.json", envMap, '../.vscode/launch.json'+debug);
            //Update Dockerfile
            var dockerMap = {
                "${_JAVA_OPTIONS}": launchEnvObject._JAVA_OPTIONS,
                "${PROBLEM_COL}": launchEnvObject.PROBLEM_COL,
                "${PROBLEM_ROW}": launchEnvObject.PROBLEM_ROW,
                "${VM_NAME}": "myDocker",
            }
            updateMulti("./templates/Dockerfile.template", dockerMap, './Dockerfile'+debug);
            //Update Kubernetes deployment
            updateEnvValues(appSettingValues).then(envString => {
                //console.log("envString:\n" + envString);
                //Update Kubernetes Deployment
                var k8DeploymentMap = {
                    "${env}": envString,
                    "${PREFIX}": appSettingValues.PREFIX
                }
                updateMulti("./templates/k8s.deployment.template.yaml", k8DeploymentMap, './k8s/deploy.yaml'+debug);
                var k8KEDAMap = {
                    "${SERVICE_BUS_QUEUE_NAME}": appSettingValues.SERVICE_BUS_QUEUE_NAME,
                    "${PREFIX}": appSettingValues.PREFIX
                }
                updateMulti("./templates/k8s.keda.template.yaml", k8KEDAMap, './k8s/keda.yaml'+debug);
                var k8SecretMap = {
                    "${SERVICE_BUS_CONNECTION_STRING}": Buffer.from(appSettingValues.SERVICE_BUS_CONNECTION_STRING).toString('base64'),
                    "${PREFIX}": appSettingValues.PREFIX
                }
                updateMulti("./templates/k8s.secret.template.yaml", k8SecretMap, './k8s/secret.yaml'+debug); 
            })
        });
    })

}

async function updateAppSettingValues(appSettingValues) {
    let appSettingValuesNew = {};
    for (const key in appSettingValues) {
        appSettingValuesNew[key.toUpperCase()] = appSettingValues[key].value;
    }
    appSettingValuesNew._JAVA_OPTIONS = `-Xmx${prop.xmx} -Xms${prop.xms}`;
    appSettingValuesNew.LOG_LEVEL = prop.LOG_LEVEL;
    return appSettingValuesNew
}

async function updateEnvValues(appSettingValues) {
    let envString = "";
    const indentionKey = "           ";
    const indentionValue = indentionKey + "  ";
    for (const key in appSettingValues) {
        if(appSettingValues[key] == "true"||appSettingValues[key] == "false"){
            envString = envString + `\n${indentionKey}- name: ${key} \n${indentionValue}value: "${appSettingValues[key]}"`;
        }else{
            envString = envString + `\n${indentionKey}- name: ${key} \n${indentionValue}value: ${appSettingValues[key]}`;
        }

    }
    envString = envString + `\n${indentionKey}- name: VM_NAME \n${indentionValue}value: myPod`;
    envString = envString + `\n${indentionKey}- name: dummyVar \n${indentionValue}value: v1`;
    return envString;
}
/*
async function update(path, key, value, target) {
    fs.readFile(path, 'utf8', (err, data) => {
        if (err) {
            console.error(err)
            return
        }
        var updateData = data.replace(new RegExp(key.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&'), "gi") , value);
        //console.log(updateData);
        write(target, updateData, );
    })

}
*/
async function updateMulti(path, map, target) {
    var updateData = "";
    fs.readFile(path, 'utf8', (err, data) => {
        if (err) {
            console.error(err)
            return
        }
        for (const key in map) {
            data = data.replace(new RegExp(key.replace(/[-\/\\^$*+?.()|[\]{}]/g, '\\$&'), "gi") , map[key]);
        }
        write(target, data);
    })

}

async function write(path, updateData) {
    try {
        await fsPromises.writeFile(path, updateData);
        console.log("Update:" + path);
    } catch (err) {
        console.error(err)
        return
    }
}