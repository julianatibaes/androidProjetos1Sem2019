package com.up.detectordeface

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AlertDialog
import android.util.SparseArray
import android.widget.ImageView
import com.google.android.gms.vision.Frame
import com.google.android.gms.vision.face.Face
import com.google.android.gms.vision.face.FaceDetector
import kotlinx.android.synthetic.main.activity_main.*
import android.Manifest
import android.content.pm.PackageManager
import android.support.v4.content.ContextCompat

class MainActivity : AppCompatActivity() {

    val PICK_IMAGE = 123

    lateinit var imageView_: ImageView
    lateinit var uri: Uri


    private val PERMISSIONS_STORAGE = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    private val REQUEST_EXTERNAL_STORAGE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        verificaPermissaoNovo(this)
        //verificaPermissao(this)

        imageView_ = findViewById(R.id.ImgFace)

        btnProcessar.setOnClickListener {

          //  val image = carregarImagem()

            val image = carregarImagemDispositivo()

            val paint = carregarModeloPaint()

            val tempBitmap = Bitmap.createBitmap(
                image.width,
                image.height,
                Bitmap.Config.RGB_565)

            val canvas = criarCanvas(tempBitmap , image)

            val faces = detectorFace(image)

            desenhaRetangularFace(faces, canvas, paint, imageView_, tempBitmap)
        }

        btnPesquisar.setOnClickListener {
            val intent = Intent(
                Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.INTERNAL_CONTENT_URI)
            startActivityForResult(Intent.createChooser(
                intent,
                "Selecione uma imagem amigão"),
                PICK_IMAGE
                )
        }
    }

    private fun verificaPermissao(activity: Activity) {
        // verifica se tem permissao de escrita
        val permission = ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        if(permission != PackageManager.PERMISSION_GRANTED){
            println("Sem permissão")
            ActivityCompat.requestPermissions(
                activity,
                PERMISSIONS_STORAGE,
                REQUEST_EXTERNAL_STORAGE
            )
        } else{
            println("Deu boa na permissão")
        }

    }

    private fun verificaPermissaoNovo(activity: Activity) {
        if (ContextCompat.checkSelfPermission(activity,
                Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {

            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE)) {

                // Show an expanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.

            } else {

                // No explanation needed, we can request the permission.

                ActivityCompat.requestPermissions(activity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_EXTERNAL_STORAGE);

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        }
    }
    private fun carregarImagemDispositivo(): Bitmap {

        val bitmap = BitmapFactory.decodeFile(getImagePath(uri))
        return bitmap

    }

    /**
     * Pega a uri com o caminho generico da imagem
     * e "diz" que é um caminho de imagem, vindo do imagem media data
     */
    private fun getImagePath(uri: Uri): String? {
        val campos = arrayOf(MediaStore.Images.Media.DATA)
        val cursor = contentResolver.query(
            uri,
            campos,
            null,
            null,
            null
        )
        cursor.moveToFirst()
        val path = cursor.getString(
            cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.DATA))
        cursor.close()
        return path
    }

    private fun desenhaRetangularFace(faces: SparseArray<Face>,
                                      canvas: Canvas,
                                      paint: Paint,
                                      imageView: ImageView,
                                      image: Bitmap) {

        for(i in 0 until faces.size()){
            val thisFace = faces.valueAt(i)
            val x1 = thisFace.position.x
            val y1 = thisFace.position.y
            val x2 = x1 + thisFace.width
            val y2 = y1 + thisFace.height
            canvas.drawRoundRect(
                RectF(x1, y1, x2, y2),
                2.toFloat(),
                2.toFloat(),
                paint)
        }
        imageView.setImageDrawable(BitmapDrawable(resources, image))
    }

    /**
     * precisamos verificar se o nosso detector está operacional antes de usá-lo.
     * Se não estiver, talvez tenhamos que aguardar a conclusão de um download ou
     * permitir que nossos usuários saibam que precisam encontrar uma conexão com a
     * Internet ou liberar algum espaço em seus dispositivos.
     */
    private fun detectorFace(bitmap: Bitmap) : SparseArray<Face> {
        val faceDetector = FaceDetector.Builder(applicationContext)
            .setTrackingEnabled(false)
            .build()
        if (!faceDetector.isOperational) AlertDialog
            .Builder(this)
            .setMessage("Não deu boa, foi mal")
            .show()
        val frame = Frame.Builder().setBitmap(bitmap).build()
        val faces = faceDetector.detect(frame)
        return faces
    }

    private fun criarCanvas(tempBitmap: Bitmap, image: Bitmap): Canvas {
        val canvas = Canvas(tempBitmap)
        canvas.drawBitmap(image, 0.toFloat(), 0.toFloat(), null )
        return canvas
    }

    private fun carregarModeloPaint(): Paint {
        val paintRet = Paint()
        //paintRet.setColor(Color.MAGENTA)
        //paintRet.setStyle(Paint.Style.STROKE)
        //paintRet.setStrokeWidth(5.toFloat())

        paintRet.color = Color.MAGENTA // define a cor
        paintRet.style = Paint.Style.STROKE // define que é um stroke
        paintRet.strokeWidth = 15.toFloat() // define a largura da linha

        return paintRet

    }

    private fun carregarImagem(): Bitmap {

        val options = BitmapFactory.Options()
        options.inMutable = true

        val bitmap = BitmapFactory.decodeResource(
            applicationContext.resources,
            R.drawable.antonio,
            options
        )

        return bitmap
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if(resultCode != Activity.RESULT_CANCELED){
            if(requestCode == PICK_IMAGE){
                val imagemSelecionada: Uri? = data?.data
                imageView_.setImageURI(imagemSelecionada)
                uri = imagemSelecionada!!
            }
        }
    }
}
